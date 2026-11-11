package vn.com.coreacademy.assistant;

import java.util.List;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.config.AssistantBudget;
import vn.com.coreacademy.assistant.tool.RetrievedChunks;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievedChunksBudgetTest {
    /** 300 characters, about 100 tokens. */
    private static ChunkHit chunk(long id) {
        return new ChunkHit(id, id, "tài liệu.pdf", 1, 1, 2, "x".repeat(300), 0.9);
    }

    @Test
    void chunksOverTheBudgetAreLeftOutWholeAndLowestRankedFirst() {
        RetrievedChunks chunks = new RetrievedChunks(250);

        var added = chunks.add(List.of(chunk(1), chunk(2), chunk(3)));

        assertThat(added).extracting(RetrievedChunks.Added::n).containsExactly(1, 2);
        assertThat(chunks.droppedForBudget()).isTrue();
    }

    @Test
    void theBudgetCountsEverySearchOfTheTurn() {
        RetrievedChunks chunks = new RetrievedChunks(250);
        chunks.add(List.of(chunk(1), chunk(2)));

        assertThat(chunks.add(List.of(chunk(3)))).isEmpty();
    }

    @Test
    void aChunkSeenBeforeIsNotCountedTwice() {
        RetrievedChunks chunks = new RetrievedChunks(250);
        chunks.add(List.of(chunk(1)));

        assertThat(chunks.add(List.of(chunk(1), chunk(2)))).extracting(RetrievedChunks.Added::n).containsExactly(2);
        assertThat(chunks.droppedForBudget()).isFalse();
    }

    @Test
    void theBudgetsAreProperties() {
        assertThat(AssistantBudget.defaults()).isEqualTo(new AssistantBudget(8192, 1200, 2000, 3500, 1000));
        assertThat(new AssistantBudget(8192, 1200, 2000, 250, 1000).fitRetrieved(List.of(chunk(1), chunk(2), chunk(3))))
            .hasSize(2);
    }
}
