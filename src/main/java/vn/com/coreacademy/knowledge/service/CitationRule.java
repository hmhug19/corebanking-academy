package vn.com.coreacademy.knowledge.service;

import java.util.List;
import java.util.Set;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

/**
 * The one citation rule of both assistants: the sources of an answer are the chunks it marks with [n]. An answer that
 * used retrieved chunks and marks none is kept as it is, with no sources and {@code grounded = false}, so the UIs can
 * label it; it is never given every chunk as a source. A marker whose chunk does not support its sentence is removed
 * first ({@link CitationSupport}).
 */
public final class CitationRule {
    /**
     * {@code found} is false when the answer is the fixed "not found" sentence; {@code grounded} is false when the
     * answer stands on retrieved chunks but marks none of them.
     */
    public record Outcome(String answer, boolean found, boolean grounded, List<CitationView> citations,
                          int checkedMarkers, int removedMarkers) {
        public Outcome(String answer, boolean found, boolean grounded, List<CitationView> citations) {
            this(answer, found, grounded, citations, 0, 0);
        }
    }

    private CitationRule() {
    }

    /**
     * Nothing retrieved, the model saying so, or an unmarked first sentence saying so in other words, is the fixed
     * sentence with no sources. Otherwise the sources are the marked chunks, numbered as the model saw them.
     */
    public static Outcome apply(String answer, List<ChunkHit> numberedHits) {
        Set<Integer> cited = CitationSelector.citedNumbers(answer, numberedHits.size());
        if (numberedHits.isEmpty() || NotFoundAnswer.isNotFound(answer)
            || cited.isEmpty() && NotFoundAnswer.saysNothingWasFound(answer)) {
            return new Outcome(NotFoundAnswer.TEXT, false, true, List.of());
        }
        if (cited.isEmpty()) {
            return new Outcome(answer, true, false, List.of());
        }

        // A marker on a sentence its chunk does not support is taken out; an answer left with none is ungrounded.
        CitationSupport.Checked checked = CitationSupport.check(answer, numberedHits);
        if (CitationSelector.citedNumbers(checked.answer(), numberedHits.size()).isEmpty()) {
            return new Outcome(checked.answer(), true, false, List.of(), checked.checked(), checked.removed());
        }

        return new Outcome(checked.answer(), true, true,
            CitationSelector.select(checked.answer(), numberedHits, checked.citingWords()), checked.checked(),
            checked.removed());
    }

    /** An earlier answer without its [n] markers: the numbers belong to that turn's chunks, not to the next one's. */
    public static String withoutMarkers(String answer) {
        return CitationSelector.stripMarkers(answer);
    }
}
