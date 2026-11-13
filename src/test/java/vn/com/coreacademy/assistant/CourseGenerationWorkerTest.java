package vn.com.coreacademy.assistant;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.agent.TurnLimiter.Admission;
import vn.com.coreacademy.assistant.config.AssistantLimits;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.course.CourseDraftService;
import vn.com.coreacademy.assistant.course.CourseGenerationWorker;
import vn.com.coreacademy.assistant.course.CourseJobRepository;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.assistant.course.GenerationStepRunner;
import vn.com.coreacademy.assistant.metrics.AssistantMetrics;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaTimeoutException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Chat keeps priority over course generation: no step starts while a chat turn runs. */
class CourseGenerationWorkerTest {
    private final CourseJobRepository jobs = mock(CourseJobRepository.class);
    private final GenerationStepRunner stepRunner = mock(GenerationStepRunner.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final TurnLimiter limiter = new TurnLimiter(new AssistantProperties(4, 20, Duration.ofMinutes(10), 2),
        new AssistantLimits(60));
    private final CourseGenerationWorker worker;

    @SuppressWarnings("unchecked")
    CourseGenerationWorkerTest() {
        ObjectProvider<GenerationStepRunner> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(stepRunner);
        when(ollama.isAvailable()).thenReturn(true);
        StepRow step = new StepRow(1, 5, "lesson", "c1l1", null, null, "running", 1, null);
        when(transaction.execute(any())).thenAnswer(invocation ->
            ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(null);
            return null;
        }).when(transaction).executeWithoutResult(any());
        when(jobs.claimNext()).thenReturn(Optional.of(step));
        when(stepRunner.run(step)).thenReturn(GenerationStepRunner.Result.of("{}", "h", 50));
        worker = new CourseGenerationWorker(jobs, provider, ollama, transaction, mock(AssistantMetrics.class), limiter);
    }

    @Test
    void noStepStartsWhileAChatTurnRunsAndOneStartsAfterItEnds() {
        TurnLimiter.Turn turn = ((Admission.Started) limiter.admit("000004")).turn();

        assertThat(worker.runOnce()).isFalse();
        verify(jobs, never()).claimNext();
        verify(stepRunner, never()).run(any());

        turn.close();

        assertThat(worker.runOnce()).isTrue();
        verify(stepRunner).run(any());
    }

    // ---- demo fix ticket 04: a slow model never loops a step forever --------------------------------------------

    @Test
    void aStepInterruptedAtItsLastAttemptFailsAtStartupInsteadOfStartingAgain() {
        StepRow spent = new StepRow(9, 5, "lesson", "c1l2", null, null, "running",
            CourseDraftService.MAX_STEP_ATTEMPTS, null);
        when(jobs.interruptedAtCap(CourseDraftService.MAX_STEP_ATTEMPTS)).thenReturn(java.util.List.of(spent));

        worker.requeueInterrupted();

        verify(jobs).failStep(9, CourseGenerationWorker.TOO_SLOW);
        verify(jobs).setStatus(5, "failed", CourseGenerationWorker.TOO_SLOW);
        verify(jobs).requeueInterrupted();
    }

    private StepRow claimedSummarise(int attempts) {
        StepRow step = new StepRow(7, 5, "summarise_sections", "", null, null, "running", attempts, null);
        when(jobs.claimNext()).thenReturn(Optional.of(step));
        return step;
    }

    @Test
    void aTimedOutStepBelowTheCapGoesBackToPendingAndKeepsItsAttempt() {
        StepRow step = claimedSummarise(1);
        when(stepRunner.run(step)).thenThrow(new OllamaTimeoutException("slow", null));

        assertThat(worker.runOnce()).isTrue();

        verify(jobs).retryStep(7);
        verify(jobs, never()).failStep(any(Long.class), any());
        verify(jobs, never()).requeueInterrupted();
    }

    @Test
    void aStepThatTimesOutAtTheCapFailsWithAMessageTheAuthorCanActOn() {
        StepRow step = claimedSummarise(CourseGenerationWorker.TIMEOUT_ATTEMPTS);
        when(stepRunner.run(step)).thenThrow(new OllamaTimeoutException("slow", null));

        worker.runOnce();

        verify(jobs).failStep(7, CourseGenerationWorker.TOO_SLOW);
        verify(jobs).setStatus(5, "failed", CourseGenerationWorker.TOO_SLOW);
        verify(jobs, never()).retryStep(any(Long.class));
    }

    @Test
    void anUnreachableModelReleasesOnlyThatStepWithoutCostingAnAttemptOrFailing() {
        StepRow step = claimedSummarise(5);
        when(stepRunner.run(step)).thenThrow(new OllamaUnavailableException("down", null));

        worker.runOnce();

        verify(jobs).releaseStep(7);
        verify(jobs, never()).requeueInterrupted();
        verify(jobs, never()).failStep(any(Long.class), any());
    }
}
