package vn.com.coreacademy.assistant.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.skill.Suggestions;
import vn.com.coreacademy.assistant.tool.FinishedAnswer;
import vn.com.coreacademy.assistant.tool.RetrievedChunks;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

import static org.assertj.core.api.Assertions.assertThat;

/** A quiz explanation never shows the suggestion block, even when the model prints it. */
class QuizExplainSuggestionsTest {
    @Test
    void anExplanationWithoutLessonTextShowsNoMarker() {
        FinishedAnswer finished = QuizExplainService.finish(new RetrievedChunks(), "Đáp án A đúng [1] vì [n] quy định.");

        assertThat(finished.answer()).isEqualTo("Đáp án A đúng vì quy định.");
        assertThat(finished.citations()).isEmpty();
    }

    @Test
    void anExplanationKeepsOnlyTheMarkersOfItsLessonChunks() {
        RetrievedChunks chunks = new RetrievedChunks();
        chunks.add(List.of(new ChunkHit(1, 42, "Bai-1.md", null, 1, 3, "CIF là mã khách hàng.", 0.9)));

        FinishedAnswer finished = QuizExplainService.finish(chunks, "CIF là mã khách hàng [1][4].");

        assertThat(finished.answer()).isEqualTo("CIF là mã khách hàng [1].");
        assertThat(finished.citations()).hasSize(1);
    }

    @Test
    void streamedTextIsCutAtTheMarkerEvenWhenItArrivesInPieces() {
        List<String> shown = new ArrayList<>();
        Consumer<String> deltas = QuizExplainService.withoutSuggestions(shown::add);

        for (String piece : List.of("Bạn chọn sai. ", "Đáp án A đúng.\n@@GO", "I_Y@@\n- Hỏi tiếp", "\n- Nữa")) {
            deltas.accept(piece);
        }

        assertThat(String.join("", shown).strip()).isEqualTo("Bạn chọn sai. Đáp án A đúng.")
            .doesNotContain(Suggestions.MARKER).doesNotContain("Hỏi tiếp");
    }
}
