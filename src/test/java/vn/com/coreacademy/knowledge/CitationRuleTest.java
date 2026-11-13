package vn.com.coreacademy.knowledge;

import java.util.List;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.tool.FinishedAnswer;
import vn.com.coreacademy.assistant.tool.RetrievedChunks;
import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.service.CitationRule;
import vn.com.coreacademy.knowledge.service.CitationSupport;
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
    void aDocumentCitationCarriesTheChunkIdTheSourceViewerOpens() {
        CitationRule.Outcome outcome = CitationRule.apply("CIF là mã khách hàng [1].", List.of(CIF, GL));

        assertThat(outcome.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo(1L);
            assertThat(citation.documentId()).isEqualTo(42L);
        });
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
    void aRefusalLaterInTheFirstParagraphIsTheFixedSentenceWithNoSources() {
        CitationRule.Outcome outcome = CitationRule.apply(
            "EOD là quy trình cuối ngày [1]. Không tìm thấy thông tin này trong tài liệu [n].", List.of(CIF, GL));

        assertThat(outcome.found()).isFalse();
        assertThat(outcome.answer()).isEqualTo(NotFoundAnswer.TEXT);
        assertThat(outcome.citations()).isEmpty();
    }

    @Test
    void aRefusalWithAMarkerAtTheStartIsStillTheFixedSentence() {
        CitationRule.Outcome outcome = CitationRule.apply("Không tìm thấy thông tin này trong tài liệu [1].",
            List.of(CIF, GL));

        assertThat(outcome.answer()).isEqualTo(NotFoundAnswer.TEXT);
        assertThat(outcome.citations()).isEmpty();
    }

    @Test
    void aMarkerThatMatchesNoChunkIsRemovedAndTheOthersStay() {
        CitationRule.Outcome outcome = CitationRule.apply("CIF là mã khách hàng [1][7].", List.of(CIF, GL));

        assertThat(outcome.answer()).isEqualTo("CIF là mã khách hàng [1].");
        assertThat(outcome.citations()).extracting(CitationView::n).containsExactly(1);
    }

    @Test
    void theLiteralPlaceholderIsNeverShownAsText() {
        CitationRule.Outcome outcome = CitationRule.apply("CIF là mã khách hàng [n].", List.of(CIF, GL));

        assertThat(outcome.answer()).isEqualTo("CIF là mã khách hàng.");
        assertThat(outcome.grounded()).isFalse();
    }

    @Test
    void anUngroundedAnswerKeepsNoMarkerOutOfRange() {
        assertThat(CitationRule.apply("Xem [9].", List.of(CIF, GL)).answer()).isEqualTo("Xem.");
    }

    @Test
    void aFinishedAnswerDropsMarkersOfNoShownSource() {
        RetrievedChunks chunks = new RetrievedChunks();
        chunks.add(List.of(CIF, GL));
        FinishedAnswer cited = chunks.finish("CIF là mã khách hàng [1].");
        FinishedAnswer stray = new FinishedAnswer("Một ý [2] và [n] khác [1].", cited.citations());

        assertThat(stray.withoutStrayMarkers().answer()).isEqualTo("Một ý và khác [1].");
        assertThat(new FinishedAnswer("Không nguồn [1].", List.of()).withoutStrayMarkers().answer())
            .isEqualTo("Không nguồn.");
    }

    @Test
    void markersAreStrippedFromHistoryText() {
        assertThat(CitationRule.withoutMarkers("A [1]. B [2][3] và C [1, 2].")).isEqualTo("A. B và C.");
        assertThat(CitationRule.withoutMarkers("Không có marker")).isEqualTo("Không có marker");
    }

    private static final ChunkHit FEES = new ChunkHit(3, 44, "Bieu-phi.pdf", 2, 10, 30,
        "Biểu phí áp dụng từ 01/2025. Phí chuyển khoản liên ngân hàng là 10.000 đồng mỗi giao dịch. "
            + "Giao dịch trên 500 triệu đồng được miễn phí. Phí rút tiền mặt tại quầy là 0,05%.",
        0.9, "Biểu phí › Chuyển khoản");

    @Test
    void aMarkerOnASentenceTheChunkDoesNotSupportIsRemovedAndCounted() {
        CitationRule.Outcome outcome = CitationRule.apply("Phí chuyển khoản liên ngân hàng là 10.000 đồng [1].",
            List.of(CIF, GL));

        assertThat(outcome.answer()).isEqualTo("Phí chuyển khoản liên ngân hàng là 10.000 đồng.");
        assertThat(outcome.removedMarkers()).isEqualTo(1);
        assertThat(outcome.checkedMarkers()).isEqualTo(1);
        assertThat(outcome.found()).isTrue();
        assertThat(outcome.grounded()).isFalse();
        assertThat(outcome.citations()).isEmpty();
    }

    @Test
    void onlyTheUnsupportedMarkersGoAndTheOtherSourcesStay() {
        CitationRule.Outcome outcome = CitationRule.apply(
            "CIF là mã khách hàng [1]. Phí chuyển khoản là 10.000 đồng [2]. Đối chiếu GL [1, 2].", List.of(CIF, GL));

        assertThat(outcome.answer()).isEqualTo(
            "CIF là mã khách hàng [1]. Phí chuyển khoản là 10.000 đồng. Đối chiếu GL [2].");
        assertThat(outcome.removedMarkers()).isEqualTo(2);
        assertThat(outcome.checkedMarkers()).isEqualTo(4);
        assertThat(outcome.grounded()).isTrue();
        assertThat(outcome.citations()).extracting(CitationView::n).containsExactly(1, 2);
    }

    @Test
    void aMarkerAfterTheFullStopStillBelongsToItsSentence() {
        CitationRule.Outcome outcome = CitationRule.apply("CIF là mã khách hàng. [1] Hoàn tiền sau 30 ngày. [2]",
            List.of(CIF, GL));

        assertThat(outcome.citations()).extracting(CitationView::n).containsExactly(1);
        assertThat(outcome.removedMarkers()).isEqualTo(1);
    }

    @Test
    void aParaphraseThatKeepsTheKeyWordsIsSupported() {
        CitationRule.Outcome outcome = CitationRule.apply(
            "Mỗi giao dịch chuyển khoản liên ngân hàng bị tính phí 10.000 đồng [1].", List.of(FEES));

        assertThat(outcome.removedMarkers()).isZero();
        assertThat(outcome.citations()).extracting(CitationView::n).containsExactly(1);
    }

    @Test
    void theSnippetIsTheSentenceOfTheChunkThatTheAnswerSaysAndNotItsOpening() {
        CitationRule.Outcome outcome = CitationRule.apply("Giao dịch trên 500 triệu đồng được miễn phí [1].",
            List.of(FEES));

        CitationView citation = outcome.citations().get(0);
        assertThat(citation.snippet()).isEqualTo("Giao dịch trên 500 triệu đồng được miễn phí.");
        assertThat(citation.sectionPath()).isEqualTo("Biểu phí › Chuyển khoản");
    }

    @Test
    void aShortBestSentenceGetsTheNextOneAndNeitherIsCutMidWord() {
        ChunkHit hit = new ChunkHit(4, 45, "a.md", null, 1, 4, "Mở đầu dài dòng. Hạn mức: 5 triệu. "
            + "Áp dụng cho khách hàng cá nhân. Kết thúc.", 0.8);
        CitationRule.Outcome outcome = CitationRule.apply("Hạn mức là 5 triệu [1].", List.of(hit));

        assertThat(outcome.citations().get(0).snippet()).isEqualTo("Hạn mức: 5 triệu. Áp dụng cho khách hàng cá nhân.");
        assertThat(outcome.citations().get(0).sectionPath()).isEmpty();
    }

    @Test
    void contentWordsAreSyllablesWithoutStopWordsAndNumbersAndCodesStayWhole() {
        assertThat(CitationSupport.contentWords("Lãi suất là 12.5% của khoản vay TK-001, và 1,5 triệu."))
            .containsExactlyInAnyOrder("lãi", "suất", "12.5%", "khoản", "vay", "tk-001", "1,5", "triệu");
    }
}
