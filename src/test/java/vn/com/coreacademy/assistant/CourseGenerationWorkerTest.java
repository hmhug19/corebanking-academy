package vn.com.coreacademy.assistant;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.agent.TurnLimiter.Admission;
import vn.com.coreacademy.assistant.config.AssistantLimits;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.course.CourseGenerationWorker;
import vn.com.coreacademy.assistant.course.CourseJobRepository;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.assistant.course.GenerationStepRunner;
import vn.com.coreacademy.assistant.metrics.AssistantMetrics;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}
