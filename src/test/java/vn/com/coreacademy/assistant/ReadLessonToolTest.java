package vn.com.coreacademy.assistant;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.agent.LessonFocus;
import vn.com.coreacademy.assistant.tool.ReadLessonTool;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.LessonSource;
import vn.com.coreacademy.learning.index.LessonReader;
import vn.com.coreacademy.learning.index.LessonReader.LessonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadLessonToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentContext CONTEXT = new AgentContext("000004", null, null,
        new LessonFocus(26, 3, 88, "Tín dụng", "Chương 1", "Bài 1"), null);

    private final LessonReader reader = mock(LessonReader.class);
    private final ReadLessonTool tool = new ReadLessonTool(reader);
    private final TurnState turn = new TurnState();

    private static ChunkHit chunk(long id, String text) {
        return new ChunkHit(id, 0L, "Tín dụng › Bài 1", null, 0, 0, text, 0.0, new LessonSource(26, "Tín dụng", 3, 1,
            "Chương 1", 88, "Bài 1", 0L, null, "/lesson/26/88"));
    }

    private String run() {
        return ((ToolOutcome.Result) tool.run(JSON.createObjectNode(), CONTEXT, turn)).text();
    }

    @Test
    void aLessonWithTextIsGivenAsNumberedPassagesAndCitedAsTheLesson() {
        when(reader.read(26, "Tín dụng", 3, 88, "Bài 1")).thenReturn(
            new LessonText(List.of(chunk(-1, "Lãi suất là chi phí vay.")), false, false));

        String result = run();

        assertThat(result).contains("[1]").contains("Lãi suất là chi phí vay.").doesNotContain("phần đầu");
        var answer = turn.finisher().orElseThrow().finish("Bài nói về lãi suất [1].");
        assertThat(answer.citations()).hasSize(1);
        assertThat(answer.citations().get(0).lesson().url()).isEqualTo("/lesson/26/88");
    }

    @Test
    void aLongLessonTellsTheModelToSayOnlyTheFirstPartWasRead() {
        when(reader.read(anyLong(), anyString(), anyLong(), anyLong(), anyString())).thenReturn(
            new LessonText(List.of(chunk(-1, "Phần đầu.")), true, false));

        assertThat(run()).contains("phần đầu của bài");
    }

    @Test
    void aLessonWithNoTextGetsTheFixedSentenceAndNoCitationWhateverTheModelWrites() {
        when(reader.read(anyLong(), anyString(), anyLong(), anyLong(), anyString())).thenReturn(
            new LessonText(List.of(), false, true));

        assertThat(run()).isEqualTo("Bài này chưa có nội dung văn bản để mình đọc.");
        var answer = turn.finisher().orElseThrow().finish("Bài này nói về một khóa khác [1].");
        assertThat(answer.answer()).isEqualTo("Bài này chưa có nội dung văn bản để mình đọc.");
        assertThat(answer.citations()).isEmpty();
    }
}
