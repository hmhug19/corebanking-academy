package vn.com.coreacademy.assistant;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import vn.com.coreacademy.assistant.action.AssistantPendingAction;
import vn.com.coreacademy.assistant.action.PendingActionRepository;
import vn.com.coreacademy.assistant.config.AssistantBudget;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.assistant.conversation.AssistantConversation;
import vn.com.coreacademy.assistant.conversation.AssistantTurn;
import vn.com.coreacademy.assistant.conversation.ConversationRepository;
import vn.com.coreacademy.assistant.conversation.ConversationRetention;
import vn.com.coreacademy.assistant.conversation.ConversationRetentionJob;
import vn.com.coreacademy.assistant.conversation.ConversationService;
import vn.com.coreacademy.assistant.conversation.FeedbackRepository;
import vn.com.coreacademy.assistant.conversation.ConversationViews.ConversationDetail;
import vn.com.coreacademy.assistant.conversation.Conversations;
import vn.com.coreacademy.assistant.conversation.TurnRecord;
import vn.com.coreacademy.assistant.conversation.TurnRepository;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.service.NotFoundAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {
    private static final String ME = "000004";
    private static final String THEM = "000005";

    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final TurnRepository turns = mock(TurnRepository.class);
    private final PendingActionRepository actions = mock(PendingActionRepository.class);
    private final MutableClock clock = new MutableClock();
    private final ConversationService service = serviceWith(clock);
    private final LogCapture logs = new LogCapture();

    private ConversationService serviceWith(MutableClock clock) {
        return newService(conversations, turns, actions, clock);
    }

    static ConversationService newService(ConversationRepository conversations, TurnRepository turns,
                                          PendingActionRepository actions, MutableClock clock) {
        return newService(conversations, turns, actions, mock(FeedbackRepository.class), clock);
    }

    static ConversationService newService(ConversationRepository conversations, TurnRepository turns,
                                          PendingActionRepository actions, FeedbackRepository feedback,
                                          MutableClock clock) {
        try {
            var constructor = ConversationService.class.getDeclaredConstructor(ConversationRepository.class,
                TurnRepository.class, PendingActionRepository.class, FeedbackRepository.class, ObjectMapper.class, java.time.Clock.class, AssistantProperties.class, AssistantBudget.class);
            constructor.setAccessible(true);
            return constructor.newInstance(conversations, turns, actions, feedback, new ObjectMapper().findAndRegisterModules(), clock,
                new AssistantProperties(4, 20, Duration.ofMinutes(10), 4), AssistantBudget.defaults());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @BeforeEach
    void stubSaves() {
        when(conversations.save(any())).thenAnswer(invocation -> {
            AssistantConversation saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(7L);
            }
            return saved;
        });
        when(turns.save(any())).thenAnswer(invocation -> {
            AssistantTurn saved = invocation.getArgument(0);
            saved.setId(31L);
            return saved;
        });
    }

    @AfterEach
    void releaseLogs() {
        logs.close();
    }

    private static AssistantConversation owned(long id, String owner) {
        AssistantConversation conversation = new AssistantConversation();
        conversation.setId(id);
        conversation.setOwnerId(owner);
        conversation.setTitle("Tiêu đề");
        return conversation;
    }

    private static AssistantTurn done(String question, String answer) {
        AssistantTurn turn = new AssistantTurn();
        turn.setQuestion(question);
        turn.setAnswer(answer);
        turn.setStatus("DONE");
        return turn;
    }

    @Test
    void aNewConversationIsTitledWithTheFirstQuestionTrimmedTo80Characters() {
        String question = "  " + "câu hỏi rất dài ".repeat(10) + "  ";

        Conversations.Opened opened = service.open(ME, null, question);

        ArgumentCaptor<AssistantConversation> saved = ArgumentCaptor.forClass(AssistantConversation.class);
        verify(conversations).save(saved.capture());
        assertThat(saved.getValue().getTitle()).hasSize(80).startsWith("câu hỏi rất dài câu hỏi");
        assertThat(saved.getValue().getOwnerId()).isEqualTo(ME);
        assertThat(opened.conversationId()).isEqualTo(7L);
        assertThat(opened.history()).isEmpty();
    }

    @Test
    void theTitleCollapsesLineBreaksSoAListRowStaysOneLine() {
        service.open(ME, null, "Dòng một\n\nDòng   hai");

        ArgumentCaptor<AssistantConversation> saved = ArgumentCaptor.forClass(AssistantConversation.class);
        verify(conversations).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("Dòng một Dòng hai");
    }

    @Test
    void anotherUsersConversationAnswersNotFoundOnEveryOperation() {
        when(conversations.findByIdAndOwnerId(9L, ME)).thenReturn(Optional.empty());
        when(conversations.existsByIdAndOwnerId(9L, ME)).thenReturn(false);

        List<Runnable> operations = List.of(
            () -> service.requireOwned(ME, 9L),
            () -> service.open(ME, 9L, "Hỏi"),
            () -> service.get(ME, 9L),
            () -> service.rename(ME, 9L, "Tên mới"),
            () -> service.delete(ME, 9L));
        for (Runnable operation : operations) {
            assertThatThrownBy(operation::run).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getResponseCode().getCode())
                .isEqualTo("ASSISTANT-CONVERSATION_NOT_FOUND");
        }
        verify(turns, never()).deleteByConversation(any());
        verify(conversations, never()).delete(any());
    }

    @Test
    void historyKeepsTheNewestWholeExchangesThatFitTheTokenBudgetOldestFirst() {
        when(conversations.findByIdAndOwnerId(5L, ME)).thenReturn(Optional.of(owned(5L, ME)));
        // Newest first, as the repository returns them: 4 exchanges of about 800 tokens each.
        when(turns.findByConversationIdAndStatusOrderByIdDesc(any(), anyString(), any(Pageable.class)))
            .thenReturn(List.of(done("q4", "x".repeat(2397)), done("q3", "x".repeat(2397)),
                done("q2", "x".repeat(2397)), done("q1", "x".repeat(2397))));

        Conversations.Opened opened = service.open(ME, 5L, "câu mới");

        // 2 000 tokens fit two exchanges of 800 tokens, not three.
        assertThat(opened.history()).extracting(Conversations.Exchange::question).containsExactly("q3", "q4");
    }

    @Test
    void anEarlierAnswerOver4000CharactersIsCutAndOnesWithoutAnAnswerAreSkipped() {
        when(conversations.findByIdAndOwnerId(5L, ME)).thenReturn(Optional.of(owned(5L, ME)));
        when(turns.findByConversationIdAndStatusOrderByIdDesc(any(), anyString(), any(Pageable.class)))
            .thenReturn(List.of(done("q2", null), done("q1", "y".repeat(9000))));

        Conversations.Opened opened = service.open(ME, 5L, "tiếp");

        assertThat(opened.history()).hasSize(1);
        assertThat(opened.history().get(0).answer()).hasSize(Conversations.MAX_ANSWER_CHARS);
    }

    @Test
    void recordingATurnStoresItAndMovesTheConversationToTheTopWithoutLoggingItsText() {
        AssistantConversation conversation = owned(7L, ME);
        when(conversations.findById(7L)).thenReturn(Optional.of(conversation));
        clock.advance(Duration.ofHours(2));
        String question = "Câu hỏi bí mật số 12345";
        String answer = "Đáp án bí mật số 67890";

        long id = service.record(7L, new TurnRecord(TurnRecord.KNOWLEDGE, question, answer,
            List.of(new CitationView(1, 3L, "a.pdf", 2, 4, 6, "đoạn trích bí mật")), List.of(), null, null,
            TurnRecord.Status.DONE, 120, 900, "qa_tai_lieu", "v1"));

        assertThat(id).isEqualTo(31L);
        ArgumentCaptor<AssistantTurn> saved = ArgumentCaptor.forClass(AssistantTurn.class);
        verify(turns).save(saved.capture());
        assertThat(saved.getValue().getQuestion()).isEqualTo(question);
        assertThat(saved.getValue().getAnswer()).isEqualTo(answer);
        assertThat(saved.getValue().getCitations()).contains("a.pdf");
        assertThat(saved.getValue().getStatus()).isEqualTo("DONE");
        assertThat(conversation.getLastActivityTime()).isEqualTo(clock.instant());
        assertThat(logs.lines()).isNotEmpty();
        assertThat(logs.lines()).allSatisfy(line -> assertThat(line)
            .doesNotContain("bí mật").doesNotContain("12345").doesNotContain("67890"));
    }

    @Test
    void earlierAnswersLoseTheirSourceMarkersBeforeTheyReachTheModel() {
        when(conversations.findByIdAndOwnerId(5L, ME)).thenReturn(Optional.of(owned(5L, ME)));
        when(turns.findByConversationIdAndStatusOrderByIdDesc(any(), anyString(), any(Pageable.class)))
            .thenReturn(List.of(done("q1", "CIF là mã khách hàng [1]. Còn GL [2][3] và [1, 2] nữa [4].")));

        Conversations.Opened opened = service.open(ME, 5L, "tiếp");

        assertThat(opened.history().get(0).answer()).isEqualTo("CIF là mã khách hàng. Còn GL và nữa.")
            .doesNotContain("[");
    }

    @Test
    void aStoredAnswerThatUsedChunksButCitedNothingReadsAsNotGroundedAfterReopening() {
        when(conversations.findByIdAndOwnerId(5L, ME)).thenReturn(Optional.of(owned(5L, ME)));
        AssistantTurn ungrounded = done("Hỏi", "CIF là mã khách hàng.");
        ungrounded.setId(1L);
        ungrounded.setRetrievalTrace("[{\"tool\":\"search_documents\",\"candidates\":1,\"given\":"
            + "[{\"kind\":\"document\",\"chunkId\":9,\"rank\":1,\"similarity\":0.8}]}]");
        AssistantTurn cited = done("Hỏi", "CIF là mã khách hàng [1].");
        cited.setId(2L);
        cited.setCitations("[{\"n\":1,\"documentId\":3,\"fileName\":\"a.pdf\",\"snippet\":\"s\"}]");
        cited.setRetrievalTrace(ungrounded.getRetrievalTrace());
        AssistantTurn noSearch = done("Chào", "Xin chào!");
        noSearch.setId(3L);
        AssistantTurn notFound = done("Hỏi", NotFoundAnswer.TEXT);
        notFound.setId(4L);
        notFound.setRetrievalTrace(ungrounded.getRetrievalTrace());
        AssistantTurn explained = done("Giải thích", "Đáp án đúng vì…");
        explained.setId(5L);
        explained.setRetrievalTrace(ungrounded.getRetrievalTrace().replace("search_documents", "explain"));
        when(turns.findByConversationIdOrderByIdAsc(5L))
            .thenReturn(List.of(ungrounded, cited, noSearch, notFound, explained));

        var views = service.get(ME, 5L).turns();

        assertThat(views).extracting(view -> view.grounded()).containsExactly(false, null, null, null, null);
    }

    @Test
    void getReturnsTheTurnsOldestFirstWithTheirStoredCitationsAndNavigation() {
        when(conversations.findByIdAndOwnerId(5L, ME)).thenReturn(Optional.of(owned(5L, ME)));
        AssistantTurn turn = done("Hỏi", "Đáp");
        turn.setId(1L);
        turn.setSurface("AGENT");
        turn.setCitations("[{\"n\":1,\"documentId\":3,\"fileName\":\"a.pdf\",\"page\":2,\"lineStart\":4,"
            + "\"lineEnd\":6,\"snippet\":\"s\"}]");
        turn.setToolNames("[\"search_documents\"]");
        turn.setNavigation("{\"path\":\"/courses\",\"title\":\"Khóa học\"}");
        when(turns.findByConversationIdOrderByIdAsc(5L)).thenReturn(List.of(turn));

        ConversationDetail detail = service.get(ME, 5L);

        assertThat(detail.turns()).hasSize(1);
        assertThat(detail.turns().get(0).citations().get(0).fileName()).isEqualTo("a.pdf");
        assertThat(detail.turns().get(0).toolNames()).containsExactly("search_documents");
        assertThat(detail.turns().get(0).navigation().path()).isEqualTo("/courses");
        assertThat(detail.turns().get(0).status()).isEqualTo("done");
    }

    private AssistantPendingAction proposal(String id, String status, Duration expiresIn, String message) {
        AssistantPendingAction action = new AssistantPendingAction();
        action.setId(id);
        action.setTool("update_profile");
        action.setTitle("Cập nhật hồ sơ cá nhân");
        action.setChanges("[{\"field\":\"positionName\",\"label\":\"Chức danh\",\"from\":\"Cũ\","
            + "\"to\":\"Mới\"}]");
        action.setStatus(status);
        action.setExpiresAt(clock.instant().plus(expiresIn));
        action.setResultMessage(message);
        return action;
    }

    @Test
    void getShowsEachProposedActionWithItsRealOutcomeAndReadsAnUnansweredOldOneAsExpired() {
        when(conversations.findByIdAndOwnerId(5L, ME)).thenReturn(Optional.of(owned(5L, ME)));
        List<AssistantTurn> stored = new java.util.ArrayList<>();
        for (String id : List.of("live", "done", "no", "bad", "late")) {
            AssistantTurn turn = done("Hỏi", "Đáp");
            turn.setId((long) stored.size() + 1);
            turn.setActionId(id);
            stored.add(turn);
        }
        AssistantTurn plain = done("Không đề xuất", "Đáp");
        plain.setId(99L);
        stored.add(plain);
        when(turns.findByConversationIdOrderByIdAsc(5L)).thenReturn(stored);
        when(actions.findAllById(any())).thenReturn(List.of(
            proposal("live", "PENDING", Duration.ofMinutes(5), null),
            proposal("done", "CONFIRMED", Duration.ofMinutes(5), "Đã cập nhật."),
            proposal("no", "CANCELLED", Duration.ofMinutes(5), null),
            proposal("bad", "FAILED", Duration.ofMinutes(5), "Không thực hiện được."),
            proposal("late", "PENDING", Duration.ofMinutes(-1), null)));

        var views = service.get(ME, 5L).turns();

        assertThat(views).extracting(view -> view.action() == null ? null : view.action().status())
            .containsExactly("pending", "confirmed", "cancelled", "failed", "expired", null);
        assertThat(views.get(1).action().message()).isEqualTo("Đã cập nhật.");
        assertThat(views.get(3).action().message()).isEqualTo("Không thực hiện được.");
        assertThat(views.get(0).action().changes().get(0).to()).isEqualTo("Mới");
        assertThat(views.get(2).action().message()).isEqualTo("Đã huỷ thay đổi");
        assertThat(views.get(4).action().message()).isEqualTo("Đề xuất đã hết hạn");
        assertThat(views.get(0).action().message()).isNull();
    }

    @Test
    void theLatestConversationOfALessonIsTheCallersOwnMostRecentOneOrNothing() {
        AssistantConversation mine = owned(7L, ME);
        mine.setContextCourseId(26L);
        mine.setContextRevisionId(3L);
        mine.setContextLessonId(88L);
        mine.setContextLabel("Bài 1");
        when(conversations.findFirstByOwnerIdAndContextLessonIdOrderByLastActivityTimeDesc(ME, 88L))
            .thenReturn(Optional.of(mine));
        when(conversations.findFirstByOwnerIdAndContextLessonIdOrderByLastActivityTimeDesc(ME, 71L))
            .thenReturn(Optional.empty());

        var found = service.latestForLesson(ME, 88L);

        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo(7L);
        assertThat(found.context().lessonId()).isEqualTo(88L);
        assertThat(service.latestForLesson(ME, 71L)).isNull();
        verify(conversations, never()).findFirstByOwnerIdAndContextLessonIdOrderByLastActivityTimeDesc(THEM, 88L);
    }

    @Test
    void deleteRemovesTheTurnsAndTheConversationAndDeleteAllOnlyTheCallers() {
        AssistantConversation mine = owned(5L, ME);
        when(conversations.findByIdAndOwnerId(5L, ME)).thenReturn(Optional.of(mine));

        service.delete(ME, 5L);
        service.deleteAll(ME);

        var order = inOrder(actions, turns, conversations);
        order.verify(actions).deleteOfConversation(5L);
        order.verify(turns).deleteByConversation(5L);
        order.verify(conversations).delete(mine);
        order.verify(actions).deleteAllByOwner(ME);
        order.verify(turns).deleteAllByOwner(ME);
        order.verify(conversations).deleteAllByOwner(ME);
        verify(actions, never()).deleteAllByOwner(THEM);
        verify(turns, never()).deleteAllByOwner(THEM);
        verify(conversations, never()).deleteAllByOwner(THEM);
    }

    @Test
    void renameKeepsAtMost80CharactersAndRefusesABlankName() {
        when(conversations.findByIdAndOwnerId(5L, ME)).thenReturn(Optional.of(owned(5L, ME)));

        assertThat(service.rename(ME, 5L, "  Tên mới  ").title()).isEqualTo("Tên mới");
        for (String bad : new String[] {"   ", "x".repeat(81)}) {
            assertThatThrownBy(() -> service.rename(ME, 5L, bad)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void listUsesTheTitleSearchOnlyWhenAQueryIsGivenAndEscapesLikeWildcards() {
        when(conversations.findByOwnerId(any(), any())).thenReturn(new PageImpl<>(List.of()));
        when(conversations.searchByTitle(any(), any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.list(ME, "  ", 0, 20);
        service.list(ME, "100%_x!", 0, 20);

        verify(conversations).findByOwnerId(any(), any());
        verify(conversations).searchByTitle(any(), org.mockito.ArgumentMatchers.eq("100!%!_x!!"), any());
    }

    @Test
    void theRetentionJobDeletesWhatWasInactiveLongerThanTheSettingAndNothingElse() {
        clock.advance(Duration.ofDays(200));
        ConversationRetentionJob job = retentionJob(Duration.ofDays(90));

        job.purge();

        Instant cutoff = clock.instant().minus(Duration.ofDays(90));
        var order = inOrder(actions, turns, conversations);
        order.verify(actions).deleteOfConversationsInactiveBefore(cutoff);
        order.verify(actions).deleteCreatedBefore(cutoff);
        order.verify(turns).deleteInactiveBefore(cutoff);
        order.verify(conversations).deleteInactiveBefore(cutoff);
        verify(conversations, never()).deleteAllByOwner(any());
    }

    private ConversationRetentionJob retentionJob(Duration retention) {
        try {
            var constructor = ConversationRetentionJob.class.getDeclaredConstructor(ConversationService.class,
                ConversationRetention.class, java.time.Clock.class);
            constructor.setAccessible(true);
            return constructor.newInstance(service, new ConversationRetention(retention), clock);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
