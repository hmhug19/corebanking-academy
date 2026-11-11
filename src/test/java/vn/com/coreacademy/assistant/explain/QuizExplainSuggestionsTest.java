package vn.com.coreacademy.assistant.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.skill.Suggestions;

import static org.assertj.core.api.Assertions.assertThat;

/** A quiz explanation never shows the suggestion block, even when the model prints it. */
class QuizExplainSuggestionsTest {
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
