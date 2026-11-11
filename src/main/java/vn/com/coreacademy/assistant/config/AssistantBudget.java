package vn.com.coreacademy.assistant.config;

import java.util.ArrayList;
import java.util.List;

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
}
