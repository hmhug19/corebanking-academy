package vn.com.coreacademy.assistant;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.com.coreacademy.assistant.config.AssistantBudget;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.assistant.course.CourseGenerationRunner;
import vn.com.coreacademy.assistant.course.CourseJobRepository;
import vn.com.coreacademy.assistant.course.CourseJobRepository.JobRow;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.assistant.course.GenerationSchemas;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.SourceTextRepository;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.learning.service.LearningAiDraftService;

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
    private final LearningAiDraftService drafts = mock(LearningAiDraftService.class);
    private final CourseGenerationRunner runner = new CourseGenerationRunner(jobs, sources, ollama, new SkillCatalog(),
        AssistantModels.defaults(), json, drafts, AssistantBudget.defaults(),
        new GenerationProperties(3, Duration.ofSeconds(3)));

    private StepRow outlineStep() {
        JobRow job = new JobRow(5, "000004", 1, 9L,
            "{\"targetLearners\":\"Giao dịch viên\",\"level\":\"BEGINNER\",\"chapters\":3,\"programmeId\":1,\"competencyId\":2}",
            "running", "outline", 20, null, null, null, null, 70L, 71L);
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
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(VALID_OUTLINE);
        ArgumentCaptor<JsonNode> format = formats();

        runner.run(outlineStep());

        verify(ollama).chat(anyList(), any(), format.capture(), any());
        assertThat(format.getValue()).isEqualTo(GenerationSchemas.format("outline"));
        assertThat(format.getValue().path("type").asText()).isEqualTo("object");
        assertThat(format.getValue().path("required")).extracting(JsonNode::asText)
            .contains("title", "summary", "objectives", "chapters");
    }

    @Test
    void theOutlineIsWrittenStraightIntoTheDraftCourseOfTheJob() {
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(VALID_OUTLINE);

        runner.run(outlineStep());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<vn.com.coreacademy.learning.dto.LearningRequests.ChapterInput>> chapters =
            ArgumentCaptor.forClass(List.class);
        verify(drafts).writeOutline(eq(70L), eq(71L), eq("000004"), eq("Quy trình mở tài khoản."), anyList(),
            chapters.capture(), any());
        assertThat(chapters.getValue()).hasSize(1);
        assertThat(chapters.getValue().get(0).lessons().get(0).title()).isEqualTo("Điều kiện");
        assertThat(chapters.getValue().get(0).lessons().get(0).blocks()).isEmpty();
    }

    @Test
    void aLessonTheAuthorEditedMeanwhileIsNotWrittenAndTheModelIsNotAsked() {
        StepRow lesson = new StepRow(3, 5, "lesson", "c1l1", null, null, "running", 1, null);
        outlineStep();
        when(drafts.startLesson(70L, 71L, 0, 0, "000004")).thenReturn(false);

        var result = runner.run(lesson);

        verify(ollama, times(0)).chat(anyList(), any(), any(), any());
        verify(drafts, times(0)).writeLesson(anyLong(), anyLong(), org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(), anyList(), any(), any());
        assertThat(result.outputJson()).contains("user_edited");
    }

    @Test
    void theOutlineWriteAsksTheJobStatusInsideTheWriteNotBeforeTheModelCall() {
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(VALID_OUTLINE);
        StepRow step = outlineStep();
        ArgumentCaptor<java.util.function.BooleanSupplier> wanted =
            ArgumentCaptor.forClass(java.util.function.BooleanSupplier.class);

        runner.run(step);

        verify(drafts).writeOutline(eq(70L), eq(71L), eq("000004"), any(), anyList(), anyList(), wanted.capture());
        assertThat(wanted.getValue().getAsBoolean()).isTrue();
        when(jobs.job(5)).thenReturn(Optional.of(jobWithStatus("stopped")));
        assertThat(wanted.getValue().getAsBoolean()).isFalse();
        when(jobs.job(5)).thenReturn(Optional.empty());
        assertThat(wanted.getValue().getAsBoolean()).isFalse();
    }

    @Test
    void theLessonWriteAsksTheJobStatusInsideTheWriteSoAStopAfterTheModelCallWritesNothing() {
        lessonFixtures();
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(
            "{\"blocks\":[{\"type\":\"text\",\"title\":\"Điều kiện\",\"body\":\"Nội dung.\"}],\"sourceNotes\":[]}");
        ArgumentCaptor<java.util.function.BooleanSupplier> wanted =
            ArgumentCaptor.forClass(java.util.function.BooleanSupplier.class);

        runner.run(new StepRow(3, 5, "lesson", "c1l1", null, null, "running", 1, null));

        verify(drafts).writeLesson(eq(70L), eq(71L), eq(0), eq(0), anyList(), eq("000004"), wanted.capture());
        assertThat(wanted.getValue().getAsBoolean()).isTrue();
        // The author stops the job after the model answered: the check made inside the write now says no.
        when(jobs.job(5)).thenReturn(Optional.of(jobWithStatus("stopped")));
        assertThat(wanted.getValue().getAsBoolean()).isFalse();
        when(jobs.job(5)).thenReturn(Optional.of(jobWithStatus("cancelled")));
        assertThat(wanted.getValue().getAsBoolean()).isFalse();
    }

    private JobRow jobWithStatus(String status) {
        return new JobRow(5, "000004", 1, 9L, "{}", status, "lesson", 40, null, null, null, null, 70L, 71L);
    }

    private void lessonFixtures() {
        when(jobs.job(5)).thenReturn(Optional.of(jobWithStatus("running")));
        when(jobs.stepByKey(anyLong(), eq("summarise_sections"), eq(""))).thenReturn(Optional.of(new StepRow(1, 5,
            "summarise_sections", "", null,
            "{\"sections\":[{\"title\":\"Mở\",\"summary\":\"Điều kiện.\",\"keyPoints\":[],\"firstPiece\":0,"
                + "\"lastPiece\":0}]}", "done", 1, null)));
        when(jobs.stepByKey(anyLong(), eq("outline"), eq(""))).thenReturn(Optional.of(new StepRow(2, 5, "outline", "",
            null, VALID_OUTLINE, "done", 1, null)));
        when(sources.pieces(9L)).thenReturn(List.of(new SourceTextRepository.Piece(0, "Điều kiện mở tài khoản.", 3,
            10, 24)));
        when(drafts.startLesson(70L, 71L, 0, 0, "000004")).thenReturn(true);
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
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(INVALID, INVALID, VALID_OUTLINE);

        var result = runner.run(outlineStep());

        verify(ollama, times(CourseGenerationRunner.ATTEMPTS)).chat(anyList(), any(), any(), any());
        assertThat(result.outputJson()).contains("Mở tài khoản");
    }

    @Test
    void aStepThatNeverGetsAValidAnswerFailsAfterThreeAttempts() {
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(INVALID);

        assertThatThrownBy(() -> runner.run(outlineStep())).isInstanceOf(GenerationException.class);

        verify(ollama, times(3)).chat(anyList(), any(), any(), any());
    }

    @Test
    void theSystemPromptIsTheStepFileOnlyWithoutTheChatRules() {
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(VALID_OUTLINE);

        runner.run(outlineStep());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OllamaClient.Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(ollama).chat(messages.capture(), any(), any(), any());
        String system = messages.getValue().get(0).content();
        assertThat(system).contains("dàn ý khóa học").doesNotContain("[n]").doesNotContain("@@GOI_Y@@");
    }

    // ---- ticket 39: budget and source notes ------------------------------------------------------------------------

    private static final int CONTEXT = 8192;
    private static final int ANSWER = 1000;

    private StepRow summariseStep() {
        when(jobs.job(5)).thenReturn(Optional.of(new JobRow(5, "000004", 1, 9L,
            "{\"targetLearners\":\"Giao dịch viên\",\"level\":\"BEGINNER\"}", "running", "summarise_sections", 0,
            null, null, null, null, 70L, 71L)));
        when(sources.status(9L)).thenReturn(Optional.of(new SourceTextRepository.Status("ready", null)));
        return new StepRow(1, 5, "summarise_sections", "", null, null, "running", 1, null);
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void aThreeHundredPageSourceIsSummarisedWithoutAnyCallAboveTheBudget() {
        StepRow step = summariseStep();
        List<SourceTextRepository.Piece> pieces = new java.util.ArrayList<>();
        for (int i = 0; i < 600; i++) {
            pieces.add(new SourceTextRepository.Piece(i, "Nội dung ".repeat(160), i / 2 + 1, i * 10 + 1, i * 10 + 10));
        }
        when(sources.pieces(9L)).thenReturn(pieces);
        String longSummary = "{\"title\":\"Phần\",\"summary\":\"" + "Ý chính. ".repeat(220)
            + "\",\"keyPoints\":[\"a\"]}";
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(longSummary);

        var result = runner.run(step);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OllamaClient.Message>> calls = ArgumentCaptor.forClass(List.class);
        verify(ollama, org.mockito.Mockito.atLeast(2)).chat(calls.capture(), any(), any(), any());
        int worst = calls.getAllValues().stream().mapToInt(call -> call.stream()
            .mapToInt(message -> vn.com.coreacademy.knowledge.chunking.TokenEstimator.estimate(message.content()))
            .sum()).max().orElseThrow();
        assertThat(worst).isLessThanOrEqualTo(CONTEXT - ANSWER);
        assertThat(calls.getAllValues().size()).isGreaterThan(12);
        assertThat(tree(result.outputJson()).path("groups").size()).isPositive();
    }

    @Test
    void theSummariseStepReportsProgressAfterEachGroupSoTheStatusLineMoves() {
        StepRow step = summariseStep();
        List<SourceTextRepository.Piece> pieces = new java.util.ArrayList<>();
        for (int i = 0; i < 600; i++) {
            pieces.add(new SourceTextRepository.Piece(i, "Nội dung ".repeat(160), i / 2 + 1, i * 10 + 1, i * 10 + 10));
        }
        when(sources.pieces(9L)).thenReturn(pieces);
        when(ollama.chat(anyList(), any(), any(), any()))
            .thenReturn("{\"title\":\"Phần\",\"summary\":\"Ý chính.\",\"keyPoints\":[\"a\"]}");

        runner.run(step);

        ArgumentCaptor<Integer> progress = ArgumentCaptor.forClass(Integer.class);
        verify(jobs, org.mockito.Mockito.atLeast(2)).setProgress(eq(5L), eq("summarise_sections"), progress.capture());
        assertThat(progress.getAllValues()).isSorted().allMatch(value -> value > 0 && value < 15);
    }

    @Test
    void aSourceNoteOutsideTheGivenPagesAndLinesIsNotStored() {
        JobRow job = new JobRow(5, "000004", 1, 9L, "{}", "running", "lesson", 40, null, null, null, null, 70L, 71L);
        when(jobs.job(5)).thenReturn(Optional.of(job));
        when(jobs.stepByKey(anyLong(), eq("summarise_sections"), eq(""))).thenReturn(Optional.of(new StepRow(1, 5,
            "summarise_sections", "", null,
            "{\"sections\":[{\"title\":\"Mở\",\"summary\":\"Điều kiện.\",\"keyPoints\":[],\"firstPiece\":0,"
                + "\"lastPiece\":0}]}", "done", 1, null)));
        when(jobs.stepByKey(anyLong(), eq("outline"), eq(""))).thenReturn(Optional.of(new StepRow(2, 5, "outline", "",
            null, VALID_OUTLINE, "done", 1, null)));
        when(sources.pieces(9L)).thenReturn(List.of(new SourceTextRepository.Piece(0, "Điều kiện mở tài khoản.", 3,
            10, 24)));
        when(drafts.startLesson(70L, 71L, 0, 0, "000004")).thenReturn(true);
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn("""
            {"blocks":[{"type":"text","title":"Điều kiện","body":"Nội dung."}],"sourceNotes":[
             {"note":"trong khoảng","page":3,"lineStart":12,"lineEnd":20},
             {"note":"sai trang","page":4,"lineStart":12,"lineEnd":20},
             {"note":"quá dòng","page":3,"lineStart":12,"lineEnd":40},
             {"note":"không nêu vị trí"}]}
            """);

        var result = runner.run(new StepRow(3, 5, "lesson", "c1l1", null, null, "running", 1, null));

        assertThat(tree(result.outputJson()).path("sourceNotes")).extracting(note -> note.path("note").asText())
            .containsExactly("trong khoảng", "không nêu vị trí");
    }
}
