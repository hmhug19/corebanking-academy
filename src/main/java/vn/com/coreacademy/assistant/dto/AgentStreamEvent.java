package vn.com.coreacademy.assistant.dto;

import java.util.List;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;

/** One NDJSON line of the agent stream; {@code type} is the first field so a client can branch on it. */
public sealed interface AgentStreamEvent {
    /** A tool started; the app shows a status line. */
    record Tool(String type, String name) implements AgentStreamEvent {
    }

    /** A piece of the answer text. */
    record Delta(String type, String text) implements AgentStreamEvent {
    }

    /** The app navigates to {@code path}, a route the server took from the user's own screens. */
    record Navigate(String type, String path, String title) implements AgentStreamEvent {
    }

    /** A data change waits for the user's confirmation. */
    record ActionRequired(String type, PendingActionView action) implements AgentStreamEvent {
    }

    /** Last line on success; {@code answer} replaces what the deltas added up to. */
    record Done(String type, String answer, List<CitationView> citations) implements AgentStreamEvent {
    }

    /** Last line on failure after the stream has started. */
    record Error(String type, String messageCode, String message) implements AgentStreamEvent {
    }

    static Tool tool(String name) {
        return new Tool("tool", name);
    }

    static Delta delta(String text) {
        return new Delta("delta", text);
    }

    static Navigate navigate(String path, String title) {
        return new Navigate("navigate", path, title);
    }

    static ActionRequired actionRequired(PendingActionView action) {
        return new ActionRequired("action_required", action);
    }

    static Done done(String answer, List<CitationView> citations) {
        return new Done("done", answer, citations);
    }

    static Error error(String messageCode, String message) {
        return new Error("error", messageCode, message);
    }
}
