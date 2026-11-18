package vn.com.coreacademy.assistant.drafting;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.assistant.course.CourseDraftService;
import vn.com.coreacademy.assistant.course.CourseGenerationWorker;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.StepNotReadyException;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;
import vn.com.coreacademy.assistant.drafting.DraftingStepProposer.Proposal;
import vn.com.coreacademy.assistant.metrics.AssistantMetrics;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaTimeoutException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;

/**
 * Proposes the drafting steps that wait, one at a time on this instance, so closing the chat or the page does not stop
 * them. Chat keeps priority: no step is claimed while a chat turn runs. It acts on rows and never reads the security
 * context; log lines carry ids only, never document or proposal text.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseDraftingWorker {
    static final String GENERIC_FAILURE = "Trợ lý không soạn được bước này. Hãy thử lại hoặc tự điền.";
    /** No step is being proposed on this instance. */
    private static final long NONE = -1;

    private final CourseDraftingRepository drafting;
    private final ObjectProvider<DraftingStepProposer> proposer;
    private final OllamaClient ollama;
    private final TransactionTemplate transaction;
    private final AssistantMetrics metrics;
    private final TurnLimiter chat;
    private final ObjectMapper json;
    private final DraftingMessages messages;
    private final GenerationProperties generation;
    /** The step this instance is proposing now: its claim may outlast one model call (step 1 summarises first). */
    private final AtomicLong running = new AtomicLong(NONE);

    /**
     * A step whose claim is older than one model call ({@code model-call-timeout}) lost its worker, which stopped with
     * the server: it waits again, and one that used every attempt fails instead of looping. A newer claim may belong
     * to another instance still calling the model, so it is left for a later run, and the step this instance is
     * proposing is never touched. Runs at start and then once per model-call timeout, so a claim interrupted just
     * before a restart is picked up too.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${app.assistant.generation.model-call-timeout}",
        initialDelayString = "${app.assistant.generation.model-call-timeout}")
    public void requeueInterrupted() {
        OffsetDateTime claimedBefore = OffsetDateTime.now().minus(generation.modelCallTimeout());
        long own = running.get();
        for (DraftingStepRow step : drafting.interruptedAtCap(CourseDraftService.MAX_STEP_ATTEMPTS, claimedBefore,
            own)) {
            log.warn("Interrupted drafting step used every attempt, giving up stepId={} attempt={}", step.id(),
                step.attempts());
            fail(step, CourseGenerationWorker.TOO_SLOW);
        }
        int requeued = drafting.requeueInterrupted(claimedBefore, own);
        if (requeued > 0) {
            log.info("Requeued interrupted drafting steps count={}", requeued);
        }
    }

    @Scheduled(fixedDelayString = "${app.assistant.generation.poll-interval}",
        initialDelayString = "${app.assistant.generation.poll-interval}")
    public void poll() {
        try {
            while (runOnce()) {
                // keep going while steps wait
            }
        } catch (RuntimeException exception) {
            log.warn("Drafting poll failed type={}", exception.getClass().getName());
        }
    }

    /** Proposes the next waiting step; false when there is none, the model is unreachable or a chat turn runs. */
    public boolean runOnce() {
        if (chat.chatRunning() || !ollama.isAvailable()) {
            return false;
        }
        Optional<DraftingStepRow> claimed = transaction.execute(status -> drafting.claimNext());
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        execute(claimed.get());

        return true;
    }

    private void execute(DraftingStepRow step) {
        running.set(step.id());
        try {
            propose(step);
        } finally {
            running.set(NONE);
        }
    }

    private void propose(DraftingStepRow step) {
        DraftingStepProposer active = proposer.getIfAvailable();
        Optional<SessionRow> session = drafting.session(step.sessionId());
        if (active == null || session.isEmpty()) {
            fail(step, GENERIC_FAILURE);
            return;
        }
        long started = System.nanoTime();
        String outcome = "done";
        try {
            Proposal proposal = active.propose(session.get(), step);
            if (Boolean.TRUE.equals(transaction.execute(status -> store(step, proposal)))) {
                // After the commit: the chat message points at a step that really holds the proposal.
                messages.proposed(session.get(), step.stepNo(), proposal.notes());
            }
        } catch (StepNotReadyException exception) {
            // The source document is still being read: the step waits for the next poll.
            outcome = "waiting";
            drafting.releaseStep(step.id(), step.claimedAt());
        } catch (OllamaTimeoutException exception) {
            if (step.attempts() >= CourseGenerationWorker.TIMEOUT_ATTEMPTS) {
                outcome = "failed";
                log.warn("Drafting step timed out, giving up stepId={} attempt={}", step.id(), step.attempts());
                fail(step, CourseGenerationWorker.TOO_SLOW);
            } else {
                outcome = "waiting";
                drafting.retryStep(step.id(), step.claimedAt());
                log.warn("Drafting step timed out, requeued stepId={} attempt={}", step.id(), step.attempts());
            }
        } catch (OllamaUnavailableException exception) {
            outcome = "waiting";
            drafting.releaseStep(step.id(), step.claimedAt());
            log.warn("Drafting step waits for the model, requeued stepId={} attempt={}", step.id(), step.attempts());
        } catch (GenerationException exception) {
            outcome = "failed";
            fail(step, exception.getMessage());
        } catch (RuntimeException exception) {
            outcome = "failed";
            log.warn("Drafting step failed stepId={} type={}", step.id(), exception.getClass().getName());
            fail(step, GENERIC_FAILURE);
        } finally {
            metrics.generationStep(DraftingStates.kind(step.stepNo()), outcome,
                (System.nanoTime() - started) / 1_000_000);
        }
    }

    /**
     * Stores the proposal unless the author stopped the step, filled it by hand or asked again meanwhile; false when
     * it was dropped.
     */
    private boolean store(DraftingStepRow step, Proposal proposal) {
        if (!drafting.storeProposal(step.id(), step.claimedAt(), write(proposal.proposal()),
            write(proposal.notes()))) {
            log.info("Drafting step changed while proposed, proposal dropped stepId={}", step.id());
            return false;
        }
        if (proposal.sourceSummary() != null) {
            drafting.setSourceSummary(step.sessionId(), write(proposal.sourceSummary()));
        }
        markAiContent(step);
        drafting.touchSession(step.sessionId());
        return true;
    }

    /**
     * An outline or questions proposal is AI content on the page from now on: the author may keep it with the page's
     * own "Lưu nháp" instead of "Đã chốt", and publishing must still ask them to confirm the review.
     */
    private void markAiContent(DraftingStepRow step) {
        if (step.stepNo() != DraftingStates.OUTLINE_STEP && step.stepNo() != DraftingStates.RULES_STEP) {
            return;
        }
        drafting.session(step.sessionId()).map(SessionRow::revisionId).ifPresent(drafting::markAiGenerated);
    }

    /**
     * The step fails and so does its session, with a message the chat and the page show as it is; a step the author
     * changed meanwhile (stopped, filled by hand, asked again) keeps what they made of it.
     */
    private void fail(DraftingStepRow step, String message) {
        boolean failed = Boolean.TRUE.equals(transaction.execute(status -> {
            if (!drafting.failStep(step.id(), step.claimedAt(), message)) {
                return false;
            }
            drafting.failSession(step.sessionId(), message);
            return true;
        }));
        if (failed) {
            drafting.session(step.sessionId()).ifPresent(session -> messages.failed(session, step.stepNo(), message));
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
