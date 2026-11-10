package vn.com.coreacademy.assistant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import vn.com.coreacademy.assistant.conversation.Conversations;
import vn.com.coreacademy.assistant.conversation.TurnRecord;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;

/** A store for the stream tests: same ownership rule as the real one, no database. */
public class InMemoryConversations implements Conversations {
    private final Map<Long, String> owners = new HashMap<>();
    private final Map<Long, List<TurnRecord>> turns = new HashMap<>();
    private long nextId = 100;

    /** The lesson the last 4-argument open was given, for assertions. */
    public vn.com.coreacademy.assistant.agent.LessonFocus lastOpenedLesson;

    /** Every turn recorded, in order, for assertions. */
    public final List<TurnRecord> recorded = new ArrayList<>();

    public TurnRecord lastRecord() {
        return recorded.get(recorded.size() - 1);
    }

    /** A conversation owned by {@code ownerId} that already holds one finished exchange. */
    public long seed(String ownerId, String question, String answer) {
        long id = nextId++;
        owners.put(id, ownerId);
        turns.put(id, new ArrayList<>(List.of(new TurnRecord(TurnRecord.AGENT, question, answer, List.of(),
            List.of(), null, null, TurnRecord.Status.DONE, null, 1, null, null))));
        return id;
    }

    @Override
    public void requireOwned(String ownerId, Long conversationId) {
        if (conversationId != null && !ownerId.equals(owners.get(conversationId))) {
            throw new BusinessException(AssistantResponseCode.CONVERSATION_NOT_FOUND);
        }
    }

    @Override
    public Opened open(String ownerId, Long conversationId, String question) {
        requireOwned(ownerId, conversationId);
        if (conversationId == null) {
            long id = nextId++;
            owners.put(id, ownerId);
            turns.put(id, new ArrayList<>());
            return new Opened(id, List.of());
        }

        List<Exchange> history = turns.get(conversationId).stream()
            .filter(turn -> turn.status() == TurnRecord.Status.DONE && turn.answer() != null)
            .map(turn -> new Exchange(turn.question(), turn.answer())).toList();
        return new Opened(conversationId, history);
    }

    @Override
    public Opened open(String ownerId, Long conversationId, String question,
                       vn.com.coreacademy.assistant.agent.LessonFocus lesson) {
        lastOpenedLesson = lesson;
        return open(ownerId, conversationId, question);
    }

    @Override
    public long record(long conversationId, TurnRecord turn) {
        turns.get(conversationId).add(turn);
        recorded.add(turn);
        return recorded.size();
    }
}
