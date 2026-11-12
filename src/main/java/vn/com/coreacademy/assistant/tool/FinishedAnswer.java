package vn.com.coreacademy.assistant.tool;

import java.util.List;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;

/**
 * The final text of a turn and the sources it shows. {@code grounded} is false for an answer that used retrieved
 * content and marked none of it.
 */
public record FinishedAnswer(String answer, List<CitationView> citations, boolean grounded) {
    public FinishedAnswer(String answer, List<CitationView> citations) {
        this(answer, citations, true);
    }
}
