package vn.com.coreacademy.assistant.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import vn.com.coreacademy.knowledge.chunking.TokenEstimator;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

/**
 * What one model call may spend of {@code num_ctx}, in tokens (estimated, see {@link TokenEstimator}): the system
 * prompt with the tool definitions, the earlier turns, the retrieved content, and what is kept for the answer through
 * {@code num_predict}. Whole exchanges and whole chunks are dropped to fit; nothing is cut mid-text.
 */
@Validated
@ConfigurationProperties("app.assistant.budget")
public record AssistantBudget(
    @Min(1024) @DefaultValue("8192") int numCtx,
    @Min(1) @DefaultValue("1200") int systemAndTools,
    @Min(1) @DefaultValue("2000") int history,
    @Min(1) @DefaultValue("3500") int retrieved,
    @Min(1) @DefaultValue("1000") int answer) {

    public static AssistantBudget defaults() {
        return new AssistantBudget(8192, 1200, 2000, 3500, 1000);
    }

    /** The chunks, best first, that fit the retrieved-content budget; the first that does not fit ends the list. */
    public List<ChunkHit> fitRetrieved(List<ChunkHit> hits) {
        List<ChunkHit> kept = new ArrayList<>();
        int used = 0;
        for (ChunkHit hit : hits) {
            used += TokenEstimator.estimate(hit.content());
            if (used > retrieved) {
                break;
            }
            kept.add(hit);
        }

        return kept;
    }

    /**
     * {@code hits} followed by their neighbours (the chunks at {@code chunk_index} plus and minus one) while the
     * retrieved-content budget allows, in rank order of the hit they sit next to. A neighbour already in the list is
     * skipped; the first one that does not fit ends the list. {@code neighbours} is keyed by the chunk id of the hit.
     */
    public List<ChunkHit> addNeighbours(List<ChunkHit> hits, Map<Long, List<ChunkHit>> neighbours) {
        List<ChunkHit> all = new ArrayList<>(hits);
        Set<String> seen = new HashSet<>();
        int used = 0;
        for (ChunkHit hit : hits) {
            seen.add(hit.key());
            used += TokenEstimator.estimate(hit.content());
        }
        for (ChunkHit hit : hits) {
            for (ChunkHit neighbour : neighbours.getOrDefault(hit.chunkId(), List.of())) {
                if (!seen.add(neighbour.key())) {
                    continue;
                }
                used += TokenEstimator.estimate(neighbour.content());
                if (used > retrieved) {
                    return all;
                }
                all.add(neighbour);
            }
        }

        return all;
    }
}
