package vn.com.coreacademy.assistant.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;

/** One NDJSON line of the agent stream; {@code type} is the first field so a client can branch on it. */
public sealed interface AgentStreamEvent {
    /** First line of every stream: the conversation this turn is stored in (new when the request named none). */
    record Conversation(String type, long conversationId) implements AgentStreamEvent {
    }

    /** A tool started; the app shows a status line. */
    record Tool(String type, String name) implements AgentStreamEvent {
    }

    /** A piece of the answer text. */
    record Delta(String type, String text) implements AgentStreamEvent {
    }

    /** The app navigates to {@code path}, a route the server took from the user's own screens. */
    record Navigate(String type, String path, String title,
                    @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> prefill) implements AgentStreamEvent {
    }

    /** A data change waits for the user's confirmation. */
    record ActionRequired(String type, PendingActionView action) implements AgentStreamEvent {
    }

    /** Last line on success; {@code answer} replaces what the deltas added up to. */
    record Done(String type, String answer, List<CitationView> citations,
                @JsonInclude(JsonInclude.Include.NON_NULL) Long turnId,
                @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> suggestions,
                @JsonInclude(JsonInclude.Include.NON_DEFAULT) boolean suggestionsReadOnly)
        implements AgentStreamEvent {
        /** The same line naming the stored turn, so the client can send feedback on it. */
        public Done withTurnId(long id) {
            return new Done(type, answer, citations, id, suggestions, suggestionsReadOnly);
        }

        /**
         * The turn read shared content (documents, courses, Community): the client sends a clicked suggestion with
         * the Q&A skill, which has no acting tool. Absent from the line when false, so older clients see no change.
         */
        public Done withSuggestionsReadOnly() {
            return new Done(type, answer, citations, turnId, suggestions, true);
        }
    }

    /** Last line on failure after the stream has started. */
    record Error(String type, String messageCode, String message) implements AgentStreamEvent {
    }

    static Conversation conversation(long conversationId) {
        return new Conversation("conversation", conversationId);
    }

    static Tool tool(String name) {
        return new Tool("tool", name);
    }

    static Delta delta(String text) {
        return new Delta("delta", text);
    }

    static Navigate navigate(String path, String title, Map<String, Object> prefill) {
        return new Navigate("navigate", path, title, prefill);
    }

    static ActionRequired actionRequired(PendingActionView action) {
        return new ActionRequired("action_required", action);
    }

    static Done done(String answer, List<CitationView> citations) {
        return new Done("done", answer, citations, null, List.of(), false);
    }

    static Done done(String answer, List<CitationView> citations, List<String> suggestions) {
        return new Done("done", answer, citations, null, suggestions, false);
    }

    static Error error(String messageCode, String message) {
        return new Error("error", messageCode, message);
    }
}
