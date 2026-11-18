package vn.com.coreacademy.assistant.drafting;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.agent.TurnLimiter.Admission;
import vn.com.coreacademy.assistant.config.AssistantLimits;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.assistant.course.CourseDraftService;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.StepNotReadyException;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;
import vn.com.coreacademy.assistant.drafting.DraftingStepProposer.Proposal;
import vn.com.coreacademy.assistant.metrics.AssistantMetrics;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
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
    private final OffsetDateTime claimedAt = OffsetDateTime.parse("2026-11-18T09:00:00.123456+07:00");
    private final DraftingStepRow step = new DraftingStepRow(7, 5, 1, "proposing", null, "[]", null, 1, claimedAt,
        false, null);
    private final SessionRow session = new SessionRow(5, "000005", 1, 9L, null, null, null, 1, "active", null, "{}",
        null, null, null);
    private final DraftingMessages messages = mock(DraftingMessages.class);
    /** Runs what the worker hands over right away, unless a test holds it to look at what was handed over. */
    private final List<Runnable> handedOver = new ArrayList<>();
    private boolean holdHandOver;
    private final TaskExecutor executor = task -> {
        if (holdHandOver) {
            handedOver.add(task);
        } else {
            task.run();
        }
    };
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
        when(drafting.storeProposal(anyLong(), any(), anyString(), anyString())).thenReturn(true);
        when(drafting.failStep(anyLong(), any(), anyString())).thenReturn(true);
        worker = new CourseDraftingWorker(drafting, provider, ollama, transaction, mock(AssistantMetrics.class),
            limiter, json, messages, new GenerationProperties(3, Duration.ofSeconds(3), Duration.ofDays(30),
            Duration.ofSeconds(90)), executor);
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
        verify(drafting).storeProposal(7, claimedAt, "{\"title\":\"Khóa\"}", "[\"Chưa chọn được chương trình\"]");
        verify(drafting, never()).failSession(anyLong(), any());
        verify(messages).proposed(session, 1, List.of("Chưa chọn được chương trình"));
        verify(messages, never()).failed(any(), anyInt(), any());
    }

    @Test
    void aReadableFailureEndsTheStepAndTheSessionWithItsMessage() {
        when(proposer.propose(session, step)).thenThrow(new GenerationException("Tài liệu quá ngắn."));

        worker.runOnce();

        verify(drafting).failStep(7, claimedAt, "Tài liệu quá ngắn.");
        verify(drafting).failSession(5, "Tài liệu quá ngắn.");
        verify(messages).failed(session, 1, "Tài liệu quá ngắn.");
        verify(messages, never()).proposed(any(), anyInt(), any());
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

        verify(drafting).releaseStep(7, claimedAt);
        verify(drafting, never()).failStep(anyLong(), any(), any());
    }

    @Test
    void aPollStopsAtAStepThatWaitsInsteadOfClaimingItAgainAndAgain() {
        // The same scheduler thread runs the document reader: a poll that spins on a waiting step starves it.
        when(proposer.propose(session, step)).thenThrow(new StepNotReadyException("reading"));

        worker.poll();

        verify(drafting, times(1)).claimNext();
        verify(drafting).releaseStep(7, claimedAt);
    }

    @Test
    void aPollStopsAtAStepThatWaitsForTheModel() {
        when(proposer.propose(session, step)).thenThrow(new OllamaUnavailableException("down", null));

        worker.poll();

        verify(drafting, times(1)).claimNext();
    }

    @Test
    void aPollHandsTheStepsToTheWorkerThreadInsteadOfProposingOnTheScheduler() {
        // The scheduler thread is shared with the document reader, quiz auto-submit and the chat room: a model call of
        // up to a minute and a half must not hold it.
        holdHandOver = true;
        when(proposer.propose(session, step)).thenReturn(new Proposal(json.createObjectNode(), List.of(), null));

        worker.poll();

        verify(drafting, never()).claimNext();
        assertThat(handedOver).hasSize(1);
        when(drafting.claimNext()).thenReturn(Optional.of(step), Optional.empty());
        handedOver.get(0).run();
        verify(drafting, times(2)).claimNext();
    }

    @Test
    void aPollWhileTheWorkerThreadIsBusyHandsNothingMoreOver() {
        holdHandOver = true;

        worker.poll();
        worker.poll();

        assertThat(handedOver).hasSize(1);
    }

    @Test
    void theWorkerThreadIsFreeAgainAfterARunThatFailed() {
        holdHandOver = true;
        when(drafting.claimNext()).thenThrow(new IllegalStateException("database down"));

        worker.poll();
        handedOver.get(0).run();
        worker.poll();

        assertThat(handedOver).hasSize(2);
    }

    @Test
    void aProposalThatArrivesAfterTheAuthorStoppedTheStepIsDropped() {
        // Dừng, Tự điền or Đề xuất lại changed the step while the model wrote: this claim no longer owns it.
        when(drafting.storeProposal(anyLong(), any(), anyString(), anyString())).thenReturn(false);
        when(proposer.propose(session, step)).thenReturn(new Proposal(json.createObjectNode(), List.of(),
            json.createObjectNode()));

        worker.runOnce();

        verify(drafting, never()).setSourceSummary(anyLong(), anyString());
        verify(drafting, never()).touchSession(anyLong());
        verify(messages, never()).proposed(any(), anyInt(), any());
    }

    @Test
    void aFailureAfterTheAuthorStoppedTheStepLeavesTheSessionAlone() {
        when(drafting.failStep(anyLong(), any(), anyString())).thenReturn(false);
        when(proposer.propose(session, step)).thenThrow(new GenerationException("Tài liệu quá ngắn."));

        worker.runOnce();

        verify(drafting, never()).failSession(anyLong(), any());
        verify(messages, never()).failed(any(), anyInt(), any());
    }

    @Test
    void onlyClaimsOlderThanOneModelCallAreRequeuedOrFailed() {
        OffsetDateTime before = OffsetDateTime.now().minusSeconds(90);

        worker.requeueInterrupted();

        // Another instance may still be calling the model for a newer claim: the cut-off is one call timeout ago.
        verify(drafting).interruptedAtCap(eq(CourseDraftService.MAX_STEP_ATTEMPTS),
            argThat(cutoff -> !cutoff.isBefore(before.minusSeconds(5)) && !cutoff.isAfter(OffsetDateTime.now()
                .minusSeconds(89))), eq(-1L));
        verify(drafting).requeueInterrupted(argThat(cutoff -> !cutoff.isBefore(before.minusSeconds(5))
            && !cutoff.isAfter(OffsetDateTime.now().minusSeconds(89))), eq(-1L));
    }

    @Test
    void theStepThisInstanceIsProposingIsNeverRequeued() {
        when(proposer.propose(session, step)).thenAnswer(invocation -> {
            worker.requeueInterrupted();
            return new Proposal(json.createObjectNode(), List.of(), null);
        });

        worker.runOnce();

        verify(drafting).requeueInterrupted(any(), eq(7L));
        worker.requeueInterrupted();
        verify(drafting).requeueInterrupted(any(), eq(-1L));
    }

    @Test
    void anOutlineOrQuestionsProposalMarksTheDraftAsAiMadeAndBasicsDoNot() {
        when(proposer.propose(any(), any())).thenReturn(new Proposal(json.createObjectNode(), List.of(), null));
        worker.runOnce();
        verify(drafting, never()).markAiGenerated(anyLong());

        SessionRow drafted = new SessionRow(5, "000005", 1, 9L, null, 40L, 41L, 2, "active", null, "{}", null, null,
            null);
        when(drafting.session(5)).thenReturn(Optional.of(drafted));
        for (int stepNo : new int[] {2, 3, 4}) {
            when(drafting.claimNext()).thenReturn(Optional.of(new DraftingStepRow(7 + stepNo, 5, stepNo, "proposing",
                null, "[]", null, 1, claimedAt, false, null)));
            worker.runOnce();
        }

        // steps 2 and 3, not step 4: the page's own "Lưu nháp" keeps them, and publishing must ask for the review
        verify(drafting, org.mockito.Mockito.times(2)).markAiGenerated(41L);
    }

    @Test
    void aDroppedOutlineProposalMarksNothing() {
        when(drafting.storeProposal(anyLong(), any(), anyString(), anyString())).thenReturn(false);
        when(drafting.session(5)).thenReturn(Optional.of(new SessionRow(5, "000005", 1, 9L, null, 40L, 41L, 2,
            "active", null, "{}", null, null, null)));
        when(drafting.claimNext()).thenReturn(Optional.of(new DraftingStepRow(9, 5, 2, "proposing", null, "[]", null,
            1, claimedAt, false, null)));
        when(proposer.propose(any(), any())).thenReturn(new Proposal(json.createObjectNode(), List.of(), null));

        worker.runOnce();

        verify(drafting, never()).markAiGenerated(anyLong());
    }

    @Test
    void aSourceStillBeingReadSendsNoMessage() {
        when(proposer.propose(session, step)).thenThrow(new StepNotReadyException("reading"));

        worker.runOnce();

        verify(messages, never()).proposed(any(), anyInt(), any());
        verify(messages, never()).failed(any(), anyInt(), any());
    }
}
