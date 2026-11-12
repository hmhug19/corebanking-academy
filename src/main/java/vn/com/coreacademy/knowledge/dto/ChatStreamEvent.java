package vn.com.coreacademy.knowledge.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;

/** One NDJSON line of the chat stream; {@code type} is the first field so a client can branch on it. */
public sealed interface ChatStreamEvent {
    /** First line of every stream: the conversation this turn is stored in (new when the request named none). */
    record Conversation(String type, long conversationId) implements ChatStreamEvent {
    }

    /** Retrieval is done and generation is starting. */
    record Retrieved(String type, int count) implements ChatStreamEvent {
    }

    /** A piece of the answer text. */
    record Delta(String type, String text) implements ChatStreamEvent {
    }

    /**
     * Last line on success. {@code answer} is the final text: what the deltas added up to, or the fixed sentence when
     * the model said nothing was found (a client replaces what it streamed). Citations are empty in that case.
     * {@code grounded} is present, as false, only for an answer that used chunks and marked none; absent means true.
     */
    record Done(String type, boolean found, String answer, List<CitationView> citations,
                @JsonInclude(JsonInclude.Include.NON_NULL) Long turnId,
                @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> suggestions,
                @JsonInclude(JsonInclude.Include.NON_NULL) Boolean grounded) implements ChatStreamEvent {
        /** The same line naming the stored turn, so the client can send feedback on it. */
        public Done withTurnId(long id) {
            return new Done(type, found, answer, citations, id, suggestions, grounded);
        }
    }

    /** Last line on failure after the stream has started. */
    record Error(String type, String messageCode, String message) implements ChatStreamEvent {
    }

    static Conversation conversation(long conversationId) {
        return new Conversation("conversation", conversationId);
    }

    static Retrieved retrieved(int count) {
        return new Retrieved("retrieved", count);
    }

    static Delta delta(String text) {
        return new Delta("delta", text);
    }

    static Done done(boolean found, String answer, List<CitationView> citations) {
        return new Done("done", found, answer, citations, null, List.of(), null);
    }

    static Done done(boolean found, String answer, List<CitationView> citations, List<String> suggestions) {
        return new Done("done", found, answer, citations, null, suggestions, null);
    }

    static Done done(boolean found, String answer, List<CitationView> citations, List<String> suggestions,
                     boolean grounded) {
        return new Done("done", found, answer, citations, null, suggestions, grounded ? null : Boolean.FALSE);
    }

    static Error error(String messageCode, String message) {
        return new Error("error", messageCode, message);
    }
}
