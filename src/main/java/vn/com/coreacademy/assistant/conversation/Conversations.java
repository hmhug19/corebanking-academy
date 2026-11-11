package vn.com.coreacademy.assistant.conversation;

import java.util.List;

import vn.com.coreacademy.assistant.agent.LessonFocus;

/**
 * What the two AI streams need from the conversation store: check the id on the request thread, open the turn once
 * the stream has been admitted, and record it when the stream ends. A fake of this keeps the stream tests off the
 * database.
 */
public interface Conversations {
    /** About 4k tokens, so the prompt, the tool definitions and the results still fit num_ctx 8192. */
    int HISTORY_CHARS = 16_000;
    /** An earlier answer longer than this is cut, since it is the server's own text. */
    int MAX_ANSWER_CHARS = 4000;
    int TITLE_CHARS = 80;

    /** One earlier question and its answer, as the model sees them. */
    record Exchange(String question, String answer) {
    }

    /** The conversation a turn belongs to (new when none was named) and its earlier turns, oldest first. */
    record Opened(long conversationId, List<Exchange> history) {
    }

    /** Throws the not-found answer unless {@code conversationId} is null or belongs to {@code ownerId}. */
    void requireOwned(String ownerId, Long conversationId);

    /**
     * Starts a new conversation titled from {@code question} when {@code conversationId} is null; otherwise loads the
     * owner's conversation. The history is the done turns that fit {@link #HISTORY_CHARS} together with the question.
     */
    Opened open(String ownerId, Long conversationId, String question);

    /**
     * As {@link #open(String, Long, String)}; a new conversation also remembers the lesson it was opened from, so
     * reopening it shows that context. An existing conversation keeps the context it started with.
     */
    default Opened open(String ownerId, Long conversationId, String question, LessonFocus lesson) {
        return open(ownerId, conversationId, question);
    }

    /** Stores the finished turn, moves the conversation to the top of the list and returns the turn id. */
    long record(long conversationId, TurnRecord turn);
}
