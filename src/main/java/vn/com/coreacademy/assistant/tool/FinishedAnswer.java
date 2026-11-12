package vn.com.coreacademy.assistant.tool;

import java.util.List;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;

/**
 * The final text of a turn and the sources it shows. {@code grounded} is false for an answer that used retrieved
 * content and marked none of it. {@code checkedMarkers} and {@code removedMarkers} count the [n] markers the support
 * check looked at and took out.
 */
public record FinishedAnswer(String answer, List<CitationView> citations, boolean grounded, int checkedMarkers,
                             int removedMarkers) {
    public FinishedAnswer(String answer, List<CitationView> citations, boolean grounded) {
        this(answer, citations, grounded, 0, 0);
    }

    public FinishedAnswer(String answer, List<CitationView> citations) {
        this(answer, citations, true);
    }
}
