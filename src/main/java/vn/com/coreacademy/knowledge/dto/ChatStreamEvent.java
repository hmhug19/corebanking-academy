package vn.com.coreacademy.knowledge.dto;

import java.util.List;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;

/** One NDJSON line of the chat stream; {@code type} is the first field so a client can branch on it. */
public sealed interface ChatStreamEvent {
    /** Retrieval is done and generation is starting. */
    record Retrieved(String type, int count) implements ChatStreamEvent {
    }

    /** A piece of the answer text. */
    record Delta(String type, String text) implements ChatStreamEvent {
    }

    /** Last line on success; citations may be empty when the model found nothing. */
    record Done(String type, boolean found, List<CitationView> citations) implements ChatStreamEvent {
    }

    /** Last line on failure after the stream has started. */
    record Error(String type, String messageCode, String message) implements ChatStreamEvent {
    }

    static Retrieved retrieved(int count) {
        return new Retrieved("retrieved", count);
    }

    static Delta delta(String text) {
        return new Delta("delta", text);
    }

    static Done done(boolean found, List<CitationView> citations) {
        return new Done("done", found, citations);
    }

    static Error error(String messageCode, String message) {
        return new Error("error", messageCode, message);
    }
}
