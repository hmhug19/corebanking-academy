package vn.com.coreacademy.assistant.course;

import java.util.Optional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.assistant.metrics.AssistantMetrics;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.NextStep;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.Result;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;

/**
 * Runs the steps of course generation one at a time, on this instance or any other: a step is claimed in the database
 * and only one job runs on the whole server, so chat waits at most one short step. It acts on rows and never reads the
 * security context. Log lines carry ids only, never document or lesson text.
 */
@Slf4j
@Component
public class CourseGenerationWorker {
    static final String GENERIC_FAILURE = "Không tạo được khóa học. Vui lòng thử lại hoặc liên hệ quản trị.";
    static final String REVIEW_STEP = "review";
    static final String NOT_SUPPORTED = "Bước này chưa được hỗ trợ.";

    private final CourseJobRepository jobs;
    private final ObjectProvider<GenerationStepRunner> runner;
    private final OllamaClient ollama;
    private final TransactionTemplate transaction;
    private final AssistantMetrics metrics;

    public CourseGenerationWorker(CourseJobRepository jobs, ObjectProvider<GenerationStepRunner> runner,
                                  OllamaClient ollama, TransactionTemplate transaction,
                                  AssistantMetrics metrics) {
        this.jobs = jobs;
        this.runner = runner;
        this.ollama = ollama;
        this.transaction = transaction;
        this.metrics = metrics;
    }

    /** A previous run that stopped mid-step leaves it running; it goes back to pending and the job resumes there. */
    @EventListener(ApplicationReadyEvent.class)
    public void requeueInterrupted() {
        int requeued = jobs.requeueInterrupted();
        if (requeued > 0) {
            log.info("Requeued interrupted course generation steps count={}", requeued);
        }
    }

    @Scheduled(fixedDelayString = "${app.assistant.generation.poll-interval}",
        initialDelayString = "${app.assistant.generation.poll-interval}")
    public void poll() {
        try {
            while (runOnce()) {
                // keep going while there is work: the next step may belong to the same job
            }
        } catch (RuntimeException exception) {
            log.warn("Course generation poll failed type={}", exception.getClass().getName());
        }
    }

    /** Runs the next step if one may run now; false when there is nothing to do or the model is unreachable. */
    public boolean runOnce() {
        if (!ollama.isAvailable()) {
            return false;
        }
        Optional<StepRow> claimed = transaction.execute(status -> jobs.claimNext());
        if (claimed == null || claimed.isEmpty()) {
            return false;
        }
        execute(claimed.get());

        return true;
    }

    private void execute(StepRow step) {
        GenerationStepRunner active = runner.getIfAvailable();
        if (active == null) {
            fail(step, NOT_SUPPORTED);
            return;
        }
        long started = System.nanoTime();
        String outcome = "done";
        try {
            Result result = active.run(step);
            transaction.executeWithoutResult(status -> finish(step, result));
        } catch (StepNotReadyException exception) {
            // The source document is still being read: try this step again at the next poll.
            outcome = "waiting";
            jobs.releaseStep(step.id());
        } catch (OllamaUnavailableException exception) {
            // The model went away mid-step: nothing is lost, the step is tried again later.
            outcome = "waiting";
            jobs.requeueInterrupted();
            log.warn("Course generation step waits for the model stepId={}", step.id());
        } catch (GenerationException exception) {
            outcome = "failed";
            fail(step, exception.getMessage());
        } catch (RuntimeException exception) {
            outcome = "failed";
            log.warn("Course generation step failed stepId={} type={}", step.id(), exception.getClass().getName());
            fail(step, GENERIC_FAILURE);
        } finally {
            metrics.generationStep(step.kind(), outcome, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private void finish(StepRow step, Result result) {
        jobs.completeStep(step.id(), result.outputJson(), result.inputHash());
        for (NextStep next : result.next()) {
            jobs.insertStep(step.jobId(), next.kind(), next.itemKey());
        }
        jobs.setProgress(step.jobId(), step.kind(), result.progress());
        if (result.jobStatus() != null) {
            jobs.setStatus(step.jobId(), result.jobStatus(), null);
        } else if (!jobs.hasOpenSteps(step.jobId())) {
            // Everything that could be generated is: the author reviews it and asks for the draft (assemble).
            jobs.setProgress(step.jobId(), REVIEW_STEP, result.progress());
            jobs.setStatus(step.jobId(), CourseJobRepository.WAITING_APPROVAL, null);
        }
    }

    private void fail(StepRow step, String message) {
        transaction.executeWithoutResult(status -> {
            jobs.failStep(step.id(), message);
            jobs.setStatus(step.jobId(), CourseJobRepository.FAILED, message);
        });
    }
}
