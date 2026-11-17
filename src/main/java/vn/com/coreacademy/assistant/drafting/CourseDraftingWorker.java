package vn.com.coreacademy.assistant.drafting;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
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
public class CourseDraftingWorker {
    static final String GENERIC_FAILURE = "Trợ lý không soạn được bước này. Hãy thử lại hoặc tự điền.";

    private final CourseDraftingRepository drafting;
    private final ObjectProvider<DraftingStepProposer> proposer;
    private final OllamaClient ollama;
    private final TransactionTemplate transaction;
    private final AssistantMetrics metrics;
    private final TurnLimiter chat;
    private final ObjectMapper json;

    public CourseDraftingWorker(CourseDraftingRepository drafting, ObjectProvider<DraftingStepProposer> proposer,
                                OllamaClient ollama, TransactionTemplate transaction, AssistantMetrics metrics,
                                TurnLimiter chat, ObjectMapper json) {
        this.drafting = drafting;
        this.proposer = proposer;
        this.ollama = ollama;
        this.transaction = transaction;
        this.metrics = metrics;
        this.chat = chat;
        this.json = json;
    }

    /** A step claimed when the server stopped waits again; one that used every attempt fails instead of looping. */
    @EventListener(ApplicationReadyEvent.class)
    public void requeueInterrupted() {
        for (DraftingStepRow step : drafting.interruptedAtCap(CourseDraftService.MAX_STEP_ATTEMPTS)) {
            log.warn("Interrupted drafting step used every attempt, giving up stepId={} attempt={}", step.id(),
                step.attempts());
            fail(step, CourseGenerationWorker.TOO_SLOW);
        }
        int requeued = drafting.requeueInterrupted();
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
            transaction.executeWithoutResult(status -> store(step, proposal));
        } catch (StepNotReadyException exception) {
            // The source document is still being read: the step waits for the next poll.
            outcome = "waiting";
            drafting.releaseStep(step.id());
        } catch (OllamaTimeoutException exception) {
            if (step.attempts() >= CourseGenerationWorker.TIMEOUT_ATTEMPTS) {
                outcome = "failed";
                log.warn("Drafting step timed out, giving up stepId={} attempt={}", step.id(), step.attempts());
                fail(step, CourseGenerationWorker.TOO_SLOW);
            } else {
                outcome = "waiting";
                drafting.retryStep(step.id());
                log.warn("Drafting step timed out, requeued stepId={} attempt={}", step.id(), step.attempts());
            }
        } catch (OllamaUnavailableException exception) {
            outcome = "waiting";
            drafting.releaseStep(step.id());
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

    private void store(DraftingStepRow step, Proposal proposal) {
        if (proposal.sourceSummary() != null) {
            drafting.setSourceSummary(step.sessionId(), write(proposal.sourceSummary()));
        }
        drafting.storeProposal(step.id(), write(proposal.proposal()), write(proposal.notes()));
        drafting.touchSession(step.sessionId());
    }

    /** The step fails and so does its session, with a message the chat and the page show as it is. */
    private void fail(DraftingStepRow step, String message) {
        transaction.executeWithoutResult(status -> {
            drafting.failStep(step.id(), message);
            drafting.failSession(step.sessionId(), message);
        });
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
