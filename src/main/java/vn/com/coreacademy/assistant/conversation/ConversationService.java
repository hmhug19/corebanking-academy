package vn.com.coreacademy.assistant.conversation;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assistant.action.AssistantPendingAction;
import vn.com.coreacademy.assistant.agent.LessonFocus;
import vn.com.coreacademy.assistant.action.PendingActionRepository;
import vn.com.coreacademy.assistant.action.PendingActionStore;
import vn.com.coreacademy.assistant.config.AssistantBudget;
import vn.com.coreacademy.assistant.config.AssistantProperties;
import vn.com.coreacademy.knowledge.chunking.TokenEstimator;
import vn.com.coreacademy.assistant.conversation.ConversationViews.ActionState;
import vn.com.coreacademy.assistant.conversation.ConversationViews.ConversationDetail;
import vn.com.coreacademy.assistant.conversation.ConversationViews.ConversationSummary;
import vn.com.coreacademy.assistant.conversation.ConversationViews.FeedbackView;
import vn.com.coreacademy.assistant.conversation.ConversationViews.LessonLabel;
import vn.com.coreacademy.assistant.conversation.ConversationViews.TurnView;
import vn.com.coreacademy.assistant.dto.PendingActionView;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.service.CitationRule;
import vn.com.coreacademy.knowledge.service.NotFoundAnswer;

/**
 * The one store behind both assistants. Every read and write is scoped to the owner; an id that is not the caller's
 * answers exactly like one that does not exist. Nothing here logs a question or an answer: only ids and counts.
 */
@Slf4j
@Service
public class ConversationService implements Conversations {
    private static final TypeReference<List<CitationView>> CITATIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<TurnRecord.Search>> SEARCHES = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> NAMES = new TypeReference<>() {
    };
    private static final TypeReference<List<PendingActionView.Change>> CHANGES = new TypeReference<>() {
    };
    private static final int HISTORY_TURNS_READ = 50;

    private final ConversationRepository conversations;
    private final TurnRepository turns;
    private final PendingActionRepository actions;
    private final FeedbackRepository feedback;
    private final ObjectMapper json;
    private final Clock clock;
    private final int historyTurns;
    private final int historyTokens;

    @Autowired
    public ConversationService(ConversationRepository conversations, TurnRepository turns,
                               PendingActionRepository actions, FeedbackRepository feedback, ObjectMapper json,
                               AssistantProperties properties, AssistantBudget budget) {
        this(conversations, turns, actions, feedback, json, Clock.systemUTC(), properties, budget);
    }

    ConversationService(ConversationRepository conversations, TurnRepository turns, PendingActionRepository actions,
                        FeedbackRepository feedback, ObjectMapper json, Clock clock, AssistantProperties properties,
                        AssistantBudget budget) {
        this.conversations = conversations;
        this.turns = turns;
        this.actions = actions;
        this.feedback = feedback;
        this.json = json;
        this.clock = clock;
        this.historyTokens = budget.history();
        this.historyTurns = Math.min(HISTORY_TURNS_READ, Math.max(1, properties.historyMessages() / 2));
    }

    @Override
    @Transactional(readOnly = true)
    public void requireOwned(String ownerId, Long conversationId) {
        if (conversationId != null && !conversations.existsByIdAndOwnerId(conversationId, ownerId)) {
            throw notFound();
        }
    }

    @Override
    @Transactional
    public Opened open(String ownerId, Long conversationId, String question) {
        return open(ownerId, conversationId, question, null);
    }

    @Override
    @Transactional
    public Opened open(String ownerId, Long conversationId, String question, LessonFocus lesson) {
        if (conversationId == null) {
            AssistantConversation created = new AssistantConversation();
            created.setOwnerId(ownerId);
            created.setTitle(titleOf(question));
            created.setLastActivityTime(clock.instant());
            if (lesson != null) {
                created.setContextCourseId(lesson.courseId());
                created.setContextRevisionId(lesson.revisionId());
                created.setContextLessonId(lesson.lessonId());
                created.setContextLabel(lesson.label());
            }
            return new Opened(conversations.save(created).getId(), List.of());
        }

        AssistantConversation conversation = conversations.findByIdAndOwnerId(conversationId, ownerId)
            .orElseThrow(ConversationService::notFound);
        return new Opened(conversation.getId(), history(conversation.getId(), question.length()));
    }

    /** The newest done turns that fit the budget with the new question, oldest first; whole exchanges only. */
    private List<Exchange> history(long conversationId, int questionChars) {
        List<AssistantTurn> recent = turns.findByConversationIdAndStatusOrderByIdDesc(conversationId,
            TurnRecord.Status.DONE.name(), PageRequest.of(0, historyTurns));
        List<Exchange> kept = new ArrayList<>();
        int budget = historyTokens;
        for (AssistantTurn turn : recent) {
            if (turn.getAnswer() == null || turn.getAnswer().isBlank()) {
                continue;
            }
            String answer = cut(CitationRule.withoutMarkers(turn.getAnswer()), MAX_ANSWER_CHARS);
            int size = TokenEstimator.estimate(turn.getQuestion()) + TokenEstimator.estimate(answer);
            if (size > budget) {
                break;
            }
            budget -= size;
            kept.add(new Exchange(turn.getQuestion(), answer));
        }
        Collections.reverse(kept);

        return kept;
    }

    @Override
    @Transactional
    public long record(long conversationId, TurnRecord turn) {
        AssistantTurn row = new AssistantTurn();
        row.setConversationId(conversationId);
        row.setSurface(turn.surface());
        row.setQuestion(turn.question());
        row.setAnswer(turn.answer());
        row.setCitations(write(turn.citations() == null ? List.of() : turn.citations()));
        row.setToolNames(write(turn.toolNames() == null ? List.of() : turn.toolNames()));
        row.setNavigation(turn.navigation() == null ? null : write(turn.navigation()));
        row.setActionId(turn.actionId());
        row.setStatus(turn.status().name());
        row.setFirstTokenMs(turn.firstTokenMs());
        row.setDurationMs(turn.durationMs());
        row.setRetrievalTrace(write(turn.retrieval() == null ? List.of() : turn.retrieval()));
        row.setSkill(turn.skill());
        row.setPromptVersion(turn.promptVersion());
        long id = turns.save(row).getId();
        conversations.findById(conversationId).ifPresent(conversation -> {
            conversation.setLastActivityTime(clock.instant());
            conversations.save(conversation);
        });
        log.info("Assistant turn stored conversationId={} turnId={} status={} questionLength={} answerLength={}",
            conversationId, id, turn.status(), turn.question().length(),
            turn.answer() == null ? 0 : turn.answer().length());

        return id;
    }

    @Transactional(readOnly = true)
    public PageResponse<ConversationSummary> list(String ownerId, String q, int page, int size) {
        PageRequest paging = PageQuery.of(page, size, "lastActivityTime,desc", Set.of("lastActivityTime"));
        String needle = q == null ? "" : q.strip();
        Page<AssistantConversation> result = needle.isEmpty()
            ? conversations.findByOwnerId(ownerId, paging)
            : conversations.searchByTitle(ownerId, escapeLike(needle), paging);

        return PageResponse.from(result.map(ConversationService::summary));
    }

    /** The caller's latest conversation about this lesson, or null: "Hỏi về bài này" continues it. */
    @Transactional(readOnly = true)
    public ConversationSummary latestForLesson(String ownerId, long lessonId) {
        return conversations.findFirstByOwnerIdAndContextLessonIdOrderByLastActivityTimeDesc(ownerId, lessonId)
            .map(ConversationService::summary).orElse(null);
    }

    @Transactional(readOnly = true)
    public ConversationDetail get(String ownerId, long conversationId) {
        AssistantConversation conversation = conversations.findByIdAndOwnerId(conversationId, ownerId)
            .orElseThrow(ConversationService::notFound);
        List<AssistantTurn> stored = turns.findByConversationIdOrderByIdAsc(conversationId);
        Map<String, AssistantPendingAction> proposed = new HashMap<>();
        actions.findAllById(stored.stream().map(AssistantTurn::getActionId).filter(Objects::nonNull).distinct()
            .toList()).forEach(action -> proposed.put(action.getId(), action));
        Map<Long, FeedbackView> rated = new HashMap<>();
        feedback.findByTurnIdIn(stored.stream().map(AssistantTurn::getId).toList()).forEach(row -> rated.put(
            row.getTurnId(), new FeedbackView(row.isHelpful(), row.getReasonCode(), row.getNote())));
        Instant now = clock.instant();
        List<TurnView> views = stored.stream()
            .map(turn -> view(turn, turn.getActionId() == null ? null : proposed.get(turn.getActionId()),
                rated.get(turn.getId()), now))
            .toList();

        return new ConversationDetail(conversation.getId(), conversation.getTitle(),
            conversation.getLastActivityTime(), conversation.getCreateTime(), label(conversation), views);
    }

    @Transactional
    public ConversationSummary rename(String ownerId, long conversationId, String title) {
        String clean = title == null ? "" : title.strip();
        if (clean.isEmpty() || clean.length() > TITLE_CHARS) {
            throw BusinessException.validation("title", "OUT_OF_RANGE",
                "Tên hội thoại phải từ 1 đến " + TITLE_CHARS + " ký tự.");
        }
        AssistantConversation conversation = conversations.findByIdAndOwnerId(conversationId, ownerId)
            .orElseThrow(ConversationService::notFound);
        conversation.setTitle(clean);

        return summary(conversations.save(conversation));
    }

    @Transactional
    public void delete(String ownerId, long conversationId) {
        AssistantConversation conversation = conversations.findByIdAndOwnerId(conversationId, ownerId)
            .orElseThrow(ConversationService::notFound);
        actions.deleteOfConversation(conversation.getId());
        feedback.deleteOfConversation(conversation.getId());
        turns.deleteByConversation(conversation.getId());
        conversations.delete(conversation);
    }

    /** Hard-deletes every conversation of {@code ownerId} and nobody else's; returns how many. */
    @Transactional
    public int deleteAll(String ownerId) {
        actions.deleteAllByOwner(ownerId);
        feedback.deleteAllByOwner(ownerId);
        turns.deleteAllByOwner(ownerId);
        return conversations.deleteAllByOwner(ownerId);
    }

    /** Hard-deletes conversations whose last activity is before {@code cutoff}, turns first; returns how many. */
    @Transactional
    public int deleteInactiveBefore(Instant cutoff) {
        actions.deleteOfConversationsInactiveBefore(cutoff);
        // Proposals no turn ever showed (the stream ended first) go on the same schedule.
        actions.deleteCreatedBefore(cutoff);
        feedback.deleteOfConversationsInactiveBefore(cutoff);
        turns.deleteInactiveBefore(cutoff);
        return conversations.deleteInactiveBefore(cutoff);
    }

    static String titleOf(String question) {
        String flat = question.strip().replaceAll("\\s+", " ");
        return cut(flat, TITLE_CHARS);
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static ConversationSummary summary(AssistantConversation conversation) {
        return new ConversationSummary(conversation.getId(), conversation.getTitle(),
            conversation.getLastActivityTime(), conversation.getCreateTime(), label(conversation));
    }

    private static LessonLabel label(AssistantConversation conversation) {
        return conversation.getContextLessonId() == null ? null
            : new LessonLabel(conversation.getContextCourseId(), conversation.getContextRevisionId(),
                conversation.getContextLessonId(), conversation.getContextLabel());
    }

    private TurnView view(AssistantTurn turn, AssistantPendingAction action, FeedbackView rating, Instant now) {
        return new TurnView(turn.getId(), turn.getSurface(), turn.getQuestion(), turn.getAnswer(),
            read(turn.getCitations(), CITATIONS, List.of()), read(turn.getToolNames(), NAMES, List.of()),
            turn.getNavigation() == null ? null
                : read(turn.getNavigation(), new TypeReference<TurnRecord.Navigation>() {
                }, null),
            turn.getActionId(), action == null ? null : state(action, now), rating,
            turn.getStatus().toLowerCase(java.util.Locale.ROOT), turn.getCreateTime(),
            ungrounded(turn) ? Boolean.FALSE : null);
    }

    /**
     * An answer that used retrieved chunks but cites none, read back from the stored turn: it has an answer, no
     * citations, a search that gave chunks, and is not the fixed "not found" sentence. A quiz explanation stands on
     * the author's text and never claims sources, so its own search does not count.
     */
    private boolean ungrounded(AssistantTurn turn) {
        if (!TurnRecord.Status.DONE.name().equals(turn.getStatus()) || turn.getAnswer() == null
            || turn.getAnswer().isBlank() || NotFoundAnswer.isNotFound(turn.getAnswer())
            || !read(turn.getCitations(), CITATIONS, List.<CitationView>of()).isEmpty()) {
            return false;
        }

        return read(turn.getRetrievalTrace(), SEARCHES, List.<TurnRecord.Search>of()).stream()
            .anyMatch(search -> !"explain".equals(search.tool()) && !search.gaveNothing());
    }

    /** The card as it stands now: a proposal nobody answered in time reads as expired, not as still waiting. */
    private ActionState state(AssistantPendingAction action, Instant now) {
        String status = PendingActionStore.PENDING.equals(action.getStatus()) && !action.getExpiresAt().isAfter(now)
            ? "expired" : action.getStatus().toLowerCase(java.util.Locale.ROOT);

        String message = action.getResultMessage();
        if (message == null || message.isBlank()) {
            // Cancelled and expired cards had no text before; give them the one a new row stores.
            message = switch (status) {
                case "cancelled" -> PendingActionStore.CANCELLED_MESSAGE;
                case "expired" -> PendingActionStore.EXPIRED_MESSAGE;
                default -> message;
            };
        }

        return new ActionState(action.getId(), action.getTool(), action.getTitle(),
            read(action.getChanges(), CHANGES, List.of()), status, action.getExpiresAt(), message);
    }

    private <T> T read(String text, TypeReference<T> type, T fallback) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException exception) {
            // A bad cell must not hide the rest of the conversation; the text itself is never logged.
            log.warn("Assistant turn JSON unreadable length={}", text.length());
            return fallback;
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialise a stored turn", exception);
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(AssistantResponseCode.CONVERSATION_NOT_FOUND);
    }
}
