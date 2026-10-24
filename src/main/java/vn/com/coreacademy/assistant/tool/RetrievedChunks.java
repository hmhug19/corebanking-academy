package vn.com.coreacademy.assistant.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.service.CitationSelector;
import vn.com.coreacademy.knowledge.service.NotFoundAnswer;

/**
 * The chunks retrieved in one turn, numbered [1]…[n] in the order they were first seen. The numbers are the ones the
 * model reads in the tool results, so they keep counting across several searches and a chunk found twice keeps its
 * first number. It also turns the model's final text into the answer and citations the app shows.
 */
final class RetrievedChunks {
    /** A chunk first seen in the latest search, with its number. */
    record Added(int n, ChunkHit hit) {
    }

    private final List<ChunkHit> hits = new ArrayList<>();
    private final Set<Long> seen = new HashSet<>();

    /** Adds the chunks not seen before in this turn and returns them with their new numbers. */
    List<Added> add(List<ChunkHit> found) {
        List<Added> added = new ArrayList<>();
        for (ChunkHit hit : found) {
            if (seen.add(hit.chunkId())) {
                hits.add(hit);
                added.add(new Added(hits.size(), hit));
            }
        }

        return added;
    }

    boolean isEmpty() {
        return hits.isEmpty();
    }

    /**
     * Nothing retrieved in the whole turn, or the model saying so, is the fixed sentence with no sources whatever else
     * the model wrote; otherwise the sources are the chunks the answer marks, from their metadata.
     */
    FinishedAnswer finish(String answer) {
        if (hits.isEmpty() || NotFoundAnswer.isNotFound(answer)) {
            return new FinishedAnswer(NotFoundAnswer.TEXT, List.of());
        }

        return new FinishedAnswer(answer, CitationSelector.select(answer, hits));
    }
}
