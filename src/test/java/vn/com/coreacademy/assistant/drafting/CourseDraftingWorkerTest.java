package vn.com.coreacademy.assistant.drafting;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.agent.TurnLimiter.Admission;
import vn.com.coreacademy.assistant.config.AssistantLimits;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.StepNotReadyException;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;
import vn.com.coreacademy.assistant.drafting.DraftingStepProposer.Proposal;
import vn.com.coreacademy.assistant.metrics.AssistantMetrics;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The drafting worker yields to chat, stores what the proposer returns and fails the session on a readable error. */
class CourseDraftingWorkerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final CourseDraftingRepository drafting = mock(CourseDraftingRepository.class);
    private final DraftingStepProposer proposer = mock(DraftingStepProposer.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final TurnLimiter limiter = new TurnLimiter(new AssistantProperties(4, 20, Duration.ofMinutes(10), 2),
        new AssistantLimits(60));
    private final DraftingStepRow step = new DraftingStepRow(7, 5, 1, "proposing", null, "[]", null, 1, null, false,
        null);
    private final SessionRow session = new SessionRow(5, "000005", 1, 9L, null, null, null, 1, "active", null, "{}",
        null, null, null);
    private final CourseDraftingWorker worker;

    @SuppressWarnings("unchecked")
    CourseDraftingWorkerTest() {
        ObjectProvider<DraftingStepProposer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(proposer);
        when(ollama.isAvailable()).thenReturn(true);
        when(transaction.execute(any())).thenAnswer(invocation ->
            ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        doAnswer(invocation -> {
            ((Consumer<TransactionStatus>) invocation.getArgument(0)).accept(null);
            return null;
        }).when(transaction).executeWithoutResult(any());
        when(drafting.claimNext()).thenReturn(Optional.of(step));
        when(drafting.session(5)).thenReturn(Optional.of(session));
        worker = new CourseDraftingWorker(drafting, provider, ollama, transaction, mock(AssistantMetrics.class),
            limiter, json);
    }

    @Test
    void noStepIsClaimedWhileAChatTurnRuns() {
        TurnLimiter.Turn turn = ((Admission.Started) limiter.admit("000004")).turn();

        assertThat(worker.runOnce()).isFalse();
        verify(drafting, never()).claimNext();

        turn.close();
        when(proposer.propose(session, step)).thenReturn(new Proposal(json.createObjectNode(), List.of(), null));
        assertThat(worker.runOnce()).isTrue();
    }

    @Test
    void aProposalIsStoredWithItsNotesAndTheSummaryItMade() {
        when(proposer.propose(session, step)).thenReturn(new Proposal(json.createObjectNode().put("title", "Khóa"),
            List.of("Chưa chọn được chương trình"), json.createObjectNode()));

        worker.runOnce();

        verify(drafting).setSourceSummary(eq(5L), anyString());
        verify(drafting).storeProposal(7, "{\"title\":\"Khóa\"}", "[\"Chưa chọn được chương trình\"]");
        verify(drafting, never()).failSession(anyLong(), any());
    }

    @Test
    void aReadableFailureEndsTheStepAndTheSessionWithItsMessage() {
        when(proposer.propose(session, step)).thenThrow(new GenerationException("Tài liệu quá ngắn."));

        worker.runOnce();

        verify(drafting).failStep(7, "Tài liệu quá ngắn.");
        verify(drafting).failSession(5, "Tài liệu quá ngắn.");
    }

    @Test
    void anUnexpectedFailureShowsAGenericMessage() {
        when(proposer.propose(session, step)).thenThrow(new IllegalStateException("secret detail"));

        worker.runOnce();

        verify(drafting).failSession(5, CourseDraftingWorker.GENERIC_FAILURE);
    }

    @Test
    void aSourceStillBeingReadReleasesTheStepWithoutFailing() {
        when(proposer.propose(session, step)).thenThrow(new StepNotReadyException("reading"));

        worker.runOnce();

        verify(drafting).releaseStep(7);
        verify(drafting, never()).failStep(anyLong(), any());
    }
}
