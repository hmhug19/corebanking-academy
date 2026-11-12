package vn.com.coreacademy.knowledge;

import java.util.List;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.tool.FinishedAnswer;
import vn.com.coreacademy.assistant.tool.RetrievedChunks;
import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.service.CitationRule;
import vn.com.coreacademy.knowledge.service.NotFoundAnswer;

import static org.assertj.core.api.Assertions.assertThat;

/** The one citation rule: the document chat and the agent must show the same sources for the same chunks. */
class CitationRuleTest {
    private static final ChunkHit CIF = new ChunkHit(1, 42, "Quy-trinh-EOD.pdf", 3, 13, 27, "CIF là mã khách hàng.", 0.9);
    private static final ChunkHit GL = new ChunkHit(2, 43, "Checklist.md", null, 1, 5, "Đối chiếu GL.", 0.8);

    private static FinishedAnswer agent(String answer, List<ChunkHit> hits) {
        RetrievedChunks chunks = new RetrievedChunks();
        chunks.add(hits);
        return chunks.finish(answer);
    }

    @Test
    void theSameAnswerAndChunksGiveTheSameSourcesOnBothPaths() {
        for (String answer : List.of("CIF [1].", "GL [2] và CIF [1, 2].", "Không có dấu nguồn.", "Xem [9].",
            NotFoundAnswer.TEXT, "Không có thông tin về phí.")) {
            CitationRule.Outcome shared = CitationRule.apply(answer, List.of(CIF, GL));
            FinishedAnswer viaAgent = agent(answer, List.of(CIF, GL));

            assertThat(viaAgent.answer()).as(answer).isEqualTo(shared.answer());
            assertThat(viaAgent.citations()).as(answer).isEqualTo(shared.citations());
            assertThat(viaAgent.grounded()).as(answer).isEqualTo(shared.grounded());
        }
    }

    @Test
    void noValidMarkerIsUngroundedWithNoSourcesAndTheAnswerKept() {
        CitationRule.Outcome outcome = CitationRule.apply("CIF là mã khách hàng.", List.of(CIF, GL));

        assertThat(outcome.found()).isTrue();
        assertThat(outcome.grounded()).isFalse();
        assertThat(outcome.answer()).isEqualTo("CIF là mã khách hàng.");
        assertThat(outcome.citations()).isEmpty();
    }

    @Test
    void markedChunksAreTheSourcesInNumberOrder() {
        CitationRule.Outcome outcome = CitationRule.apply("GL [2], CIF [1].", List.of(CIF, GL));

        assertThat(outcome.grounded()).isTrue();
        assertThat(outcome.citations()).extracting(CitationView::n).containsExactly(1, 2);
    }

    @Test
    void notFoundIsTheFixedSentenceWithNoSourcesAndStillGrounded() {
        CitationRule.Outcome outcome = CitationRule.apply("Không có thông tin về phí.", List.of(CIF));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.answer()).isEqualTo(NotFoundAnswer.TEXT);
        assertThat(outcome.grounded()).isTrue();
        assertThat(outcome.citations()).isEmpty();
    }

    @Test
    void markersAreStrippedFromHistoryText() {
        assertThat(CitationRule.withoutMarkers("A [1]. B [2][3] và C [1, 2].")).isEqualTo("A. B và C.");
        assertThat(CitationRule.withoutMarkers("Không có marker")).isEqualTo("Không có marker");
    }
}
