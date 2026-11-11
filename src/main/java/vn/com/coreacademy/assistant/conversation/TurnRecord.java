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
    int durationMs,
    String skill,
    String promptVersion,
    List<Search> retrieval) {
    /** The old shape, for a turn that retrieved nothing. */
    public TurnRecord(String surface, String question, String answer, List<CitationView> citations,
                      List<String> toolNames, Navigation navigation, String actionId, Status status,
                      Integer firstTokenMs, int durationMs, String skill, String promptVersion) {
        this(surface, question, answer, citations, toolNames, navigation, actionId, status, firstTokenMs, durationMs,
            skill, promptVersion, List.of());
    }

    public static final String AGENT = "AGENT";
    public static final String KNOWLEDGE = "KNOWLEDGE";

    public enum Status { DONE, ERROR, STOPPED }

    public record Navigation(String path, String title) {
    }

    /**
     * One search of the turn, for finding out why an answer was good or bad: the tool, how many candidates it found,
     * and the chunks given to the model. Ids and numbers only; no question, answer or chunk text.
     */
    public record Search(String tool, int candidates, List<Given> given) {
        public boolean gaveNothing() {
            return given.isEmpty();
        }
    }

    /** A chunk given to the model: {@code kind} is {@code document} or {@code lesson}, {@code rank} its [n] number. */
    public record Given(String kind, long chunkId, int rank, double similarity) {
    }
}
