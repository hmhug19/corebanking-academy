package vn.com.coreacademy.assistant;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.course.CourseGenerationRunner;
import vn.com.coreacademy.assistant.course.CourseJobRepository;
import vn.com.coreacademy.assistant.course.CourseJobRepository.JobRow;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.assistant.course.GenerationSchemas;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.SourceTextRepository;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseGenerationRunnerTest {
    private static final String VALID_OUTLINE = """
        {"title":"Mở tài khoản","summary":"Quy trình mở tài khoản.","objectives":["Hiểu quy trình"],
         "chapters":[{"title":"Chương 1","lessons":[{"title":"Điều kiện","objectives":["Biết điều kiện"],
         "estimatedMinutes":10}]}]}
        """;
    private static final String INVALID = "{\"title\":\"\"}";

    private final ObjectMapper json = new ObjectMapper();
    private final CourseJobRepository jobs = mock(CourseJobRepository.class);
    private final SourceTextRepository sources = mock(SourceTextRepository.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final CourseGenerationRunner runner = new CourseGenerationRunner(jobs, sources, ollama, new SkillCatalog(),
        AssistantModels.defaults(), json);

    private StepRow outlineStep() {
        JobRow job = new JobRow(5, "000004", 1, 9L,
            "{\"targetLearners\":\"Giao dịch viên\",\"level\":\"BEGINNER\",\"chapters\":3,\"programmeId\":1,\"competencyId\":2}",
            "running", "outline", 20, null, null, null, null);
        when(jobs.job(5)).thenReturn(Optional.of(job));
        when(jobs.stepByKey(anyLong(), eq("summarise_sections"), eq(""))).thenReturn(Optional.of(new StepRow(1, 5,
            "summarise_sections", "", null, "{\"sections\":[{\"title\":\"Mở\",\"summary\":\"Nội dung.\"}]}", "done", 1,
            null)));
        return new StepRow(2, 5, "outline", "", null, null, "running", 1, null);
    }

    private ArgumentCaptor<JsonNode> formats() {
        return ArgumentCaptor.forClass(JsonNode.class);
    }

    @Test
    void everyStepAsksOllamaForItsOwnSchema() {
        when(ollama.chat(anyList(), any(), any())).thenReturn(VALID_OUTLINE);
        ArgumentCaptor<JsonNode> format = formats();

        runner.run(outlineStep());

        verify(ollama).chat(anyList(), any(), format.capture());
        assertThat(format.getValue()).isEqualTo(GenerationSchemas.format("outline"));
        assertThat(format.getValue().path("type").asText()).isEqualTo("object");
        assertThat(format.getValue().path("required")).extracting(JsonNode::asText)
            .contains("title", "summary", "objectives", "chapters");
    }

    @Test
    void theFourStepsHaveASchemaThatNamesTheirRequiredFields() {
        assertThat(GenerationSchemas.format("summarise_sections").path("required")).extracting(JsonNode::asText)
            .contains("title", "summary", "keyPoints");
        assertThat(GenerationSchemas.format("lesson").path("required")).extracting(JsonNode::asText)
            .contains("blocks", "sourceNotes");
        assertThat(GenerationSchemas.format("questions").path("required")).extracting(JsonNode::asText)
            .contains("questions");
        JsonNode lessonItem = GenerationSchemas.format("outline").path("properties").path("chapters").path("items")
            .path("properties").path("lessons").path("items").path("properties").path("estimatedMinutes");
        assertThat(lessonItem.path("minimum").asInt()).isEqualTo(1);
        assertThat(lessonItem.path("maximum").asInt()).isEqualTo(240);
    }

    @Test
    void aStepAsksUpToThreeTimesAndTheSecondValidAnswerIsKept() {
        when(ollama.chat(anyList(), any(), any())).thenReturn(INVALID, INVALID, VALID_OUTLINE);

        var result = runner.run(outlineStep());

        verify(ollama, times(CourseGenerationRunner.ATTEMPTS)).chat(anyList(), any(), any());
        assertThat(result.outputJson()).contains("Mở tài khoản");
    }

    @Test
    void aStepThatNeverGetsAValidAnswerFailsAfterThreeAttempts() {
        when(ollama.chat(anyList(), any(), any())).thenReturn(INVALID);

        assertThatThrownBy(() -> runner.run(outlineStep())).isInstanceOf(GenerationException.class);

        verify(ollama, times(3)).chat(anyList(), any(), any());
    }

    @Test
    void theSystemPromptIsTheStepFileOnlyWithoutTheChatRules() {
        when(ollama.chat(anyList(), any(), any())).thenReturn(VALID_OUTLINE);

        runner.run(outlineStep());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OllamaClient.Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(ollama).chat(messages.capture(), any(), any());
        String system = messages.getValue().get(0).content();
        assertThat(system).contains("dàn ý khóa học").doesNotContain("[n]").doesNotContain("@@GOI_Y@@");
    }
}
