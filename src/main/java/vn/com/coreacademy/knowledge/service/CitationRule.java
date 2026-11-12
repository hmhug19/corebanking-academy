package vn.com.coreacademy.knowledge.service;

import java.util.List;
import java.util.Set;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

/**
 * The one citation rule of both assistants: the sources of an answer are the chunks it marks with [n]. An answer that
 * used retrieved chunks and marks none is kept as it is, with no sources and {@code grounded = false}, so the UIs can
 * label it; it is never given every chunk as a source.
 */
public final class CitationRule {
    /**
     * {@code found} is false when the answer is the fixed "not found" sentence; {@code grounded} is false when the
     * answer stands on retrieved chunks but marks none of them.
     */
    public record Outcome(String answer, boolean found, boolean grounded, List<CitationView> citations) {
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

        return new Outcome(answer, true, true, CitationSelector.select(answer, numberedHits));
    }

    /** An earlier answer without its [n] markers: the numbers belong to that turn's chunks, not to the next one's. */
    public static String withoutMarkers(String answer) {
        return CitationSelector.stripMarkers(answer);
    }
}
