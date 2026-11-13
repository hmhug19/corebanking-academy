package vn.com.coreacademy.assistant.tool;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.service.CitationSelector;

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

    /** The same answer without the markers that name none of its sources, the literal [n] included. */
    public FinishedAnswer withoutStrayMarkers() {
        Set<Integer> shown = citations.stream().map(CitationView::n).collect(Collectors.toSet());

        return new FinishedAnswer(CitationSelector.keepMarkers(answer, shown), citations, grounded, checkedMarkers,
            removedMarkers);
    }
}
