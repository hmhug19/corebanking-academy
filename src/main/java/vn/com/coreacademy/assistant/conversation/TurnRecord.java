package vn.com.coreacademy.assistant.conversation;

import java.util.List;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;

/** What one finished turn leaves in the store. {@code answer} is null when the turn ended before it had one. */
public record TurnRecord(
    String surface,
    String question,
    String answer,
    List<CitationView> citations,
    List<String> toolNames,
    Navigation navigation,
    String actionId,
    Status status,
    Integer firstTokenMs,
    int durationMs) {
    public static final String AGENT = "AGENT";
    public static final String KNOWLEDGE = "KNOWLEDGE";

    public enum Status { DONE, ERROR, STOPPED }

    public record Navigation(String path, String title) {
    }
}
