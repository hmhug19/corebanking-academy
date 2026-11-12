package vn.com.coreacademy.assistant;

import vn.com.coreacademy.assistant.course.CourseDraftService;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.assistant.course.CourseGenerationWorker;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;

/** The review half of course generation, against a model that answers from a script. */
class CourseDraftIT extends AssessmentIntegrationTest {
    private static final String JOBS = "/api/v1/assistant/course-jobs";
    private static final String SUMMARY = "{\"title\":\"Phần 1\",\"summary\":\"Tóm tắt\",\"keyPoints\":[\"a\"]}";
    private static final String OUTLINE = """
        {"title":"Khóa học thử","summary":"Mô tả","objectives":["Hiểu quy trình"],
         "chapters":[{"title":"Chương 1","lessons":[
           {"title":"Bài 1","objectives":["Biết A"],"estimatedMinutes":10},
           {"title":"Bài 2","objectives":["Biết B"],"estimatedMinutes":15}]}]}""";
    private static final String QUESTIONS = """
        {"questions":[
          {"type":"SINGLE","content":"Câu 1?","explanation":"Vì A","difficulty":"EASY",
           "options":[{"content":"Đúng","correct":true},{"content":"Sai","correct":false}]},
          {"type":"SINGLE","content":"Câu 2?","explanation":"Vì B","difficulty":"MEDIUM",
           "options":[{"content":"Đúng","correct":true},{"content":"Sai","correct":false}]}]}""";

    @MockitoBean
    private OllamaClient ollama;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CourseGenerationWorker worker;

    private ApiClient api;
    private long programme;
    private long competency;
    private final AtomicInteger lessonCalls = new AtomicInteger();
    private volatile boolean badLessons;
    private final AtomicInteger badAnswers = new AtomicInteger();

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        fixtures.course();
        competency = fixtures.competency();
        programme = jdbc.queryForObject("SELECT min(program_id) FROM learning_hub.training_programs", Long.class);
    }

    @BeforeEach
    void script() {
        lessonCalls.set(0);
        badAnswers.set(0);
        badLessons = false;
        when(ollama.isAvailable()).thenReturn(true);
        when(ollama.chat(any(), any(), any())).thenAnswer(invocation -> {
            List<Message> messages = invocation.getArgument(0);
            if (messages == null) {
                return null;
            }
            String user = messages.get(1).content();
            if (user.startsWith("NGUỒN")) {
                return SUMMARY;
            }
            if (user.startsWith("DỮ LIỆU\nĐối tượng")) {
                return OUTLINE;
            }
            if (user.startsWith("DỮ LIỆU\nKhóa học")) {
                if (badLessons) {
                    badAnswers.incrementAndGet();
                    return "không phải json";
                }
                int call = lessonCalls.incrementAndGet();
                return "{\"blocks\":[{\"type\":\"text\",\"title\":\"Khái niệm\",\"body\":\"Nội dung lần " + call
                    + "\"}],\"sourceNotes\":[{\"note\":\"Ghi chú nguồn\"}]}";
            }
            return QUESTIONS;
        });
        jdbc.update("DELETE FROM learning_hub.course_generation_jobs");
    }

    private long startJob() throws Exception {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("targetLearners", "Giao dịch viên");
        inputs.put("level", "BEGINNER");
        inputs.put("chapters", 1);
        inputs.put("programmeId", programme);
        inputs.put("competencyId", competency);
        JsonNode job = api.ok(INSTRUCTOR, multipart(JOBS)
            .file(new MockMultipartFile("file", "q.md", "text/markdown", "# Q\n\nNội dung.\n"
                .getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("inputs", "", "application/json", json.writeValueAsBytes(inputs))));
        long documentId = job.path("source").path("documentId").asLong();
        jdbc.update("""
            INSERT INTO learning_hub.knowledge_document_chunks(document_id, chunk_index, content, page_number,
                line_start, line_end, token_estimate, embedding)
            VALUES (?, 0, 'Quy trình mở tài khoản gồm hai bước.', NULL, 1, 3, 10, array_fill(0, ARRAY[1024])::vector)
            """, documentId);
        jdbc.update("UPDATE learning_hub.knowledge_documents SET status = 'ready', chunk_count = 1 "
            + "WHERE document_id = ?", documentId);

        return job.path("jobId").asLong();
    }

    private void runAll() {
        int guard = 0;
        while (worker.runOnce() && guard++ < 50) {
            // drain
        }
    }

    private String path(long job, String suffix) {
        return JOBS + "/" + job + suffix;
    }

    private JsonNode approved() throws Exception {
        long job = startJob();
        runAll();
        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/outline/approve")));
        runAll();

        return api.ok(INSTRUCTOR, get(path(job, "/content")));
    }

    @Test
    void nothingAfterTheOutlineRunsBeforeApprovalAndTheOutlineLocksAfterwards() throws Exception {
        long job = startJob();
        runAll();

        JsonNode content = api.ok(INSTRUCTOR, get(path(job, "/content")));
        assertThat(content.path("status").asText()).isEqualTo("waiting_approval");
        assertThat(content.path("outlineLocked").asBoolean()).isFalse();
        assertThat(content.path("lessons")).isEmpty();
        assertThat(lessonCalls.get()).isZero();

        Map<String, Object> edited = json.convertValue(content.path("outline"), Map.class);
        edited.put("title", "Tên mới");
        api.ok(INSTRUCTOR, api.put(edited, path(job, "/outline")));
        api.error(INSTRUCTOR, api.put(Map.of("title", "x"), path(job, "/outline")), "CORE-VALIDATION_FAILED");

        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/outline/approve")));
        runAll();
        JsonNode after = api.ok(INSTRUCTOR, get(path(job, "/content")));
        assertThat(after.path("outlineLocked").asBoolean()).isTrue();
        assertThat(after.path("outline").path("title").asText()).isEqualTo("Tên mới");
        assertThat(after.path("lessons")).hasSize(2);
        assertThat(after.path("questions")).hasSize(2);
        api.error(INSTRUCTOR, api.put(edited, path(job, "/outline")), "CORE-VALIDATION_FAILED");
    }

    @Test
    void theStoredOutlineKeepsOnlyKnownFieldsAndHasABoundedLessonCount() throws Exception {
        long job = startJob();
        runAll();
        JsonNode content = api.ok(INSTRUCTOR, get(path(job, "/content")));

        Map<String, Object> edited = json.convertValue(content.path("outline"), Map.class);
        edited.put("injected", "x".repeat(1000));
        api.ok(INSTRUCTOR, api.put(edited, path(job, "/outline")));
        assertThat(api.ok(INSTRUCTOR, get(path(job, "/content"))).path("outline").has("injected")).isFalse();

        java.util.List<Map<String, Object>> chapters = new java.util.ArrayList<>();
        for (int c = 0; c < 7; c++) {
            java.util.List<Map<String, Object>> lessons = new java.util.ArrayList<>();
            for (int l = 0; l < 10; l++) {
                lessons.add(Map.of("title", "B" + l, "objectives", java.util.List.of(), "estimatedMinutes", 5));
            }
            chapters.add(Map.of("title", "C" + c, "lessons", lessons));
        }
        edited.put("chapters", chapters);
        api.error(INSTRUCTOR, api.put(edited, path(job, "/outline")), "CORE-VALIDATION_FAILED");
    }

    @Test
    void theDraftWaitsForTheQuestionsOfEveryLesson() throws Exception {
        long job = approved().path("jobId").asLong();
        jdbc.update("UPDATE learning_hub.course_generation_steps SET status = 'pending' "
            + "WHERE job_id = ? AND kind = 'questions' AND item_key = 'c1l1'", job);

        api.error(INSTRUCTOR, api.post(Map.of(), path(job, "/draft")), "CORE-VALIDATION_FAILED");
    }

    @Test
    void aLessonCannotBeRegeneratedWithoutEnd() throws Exception {
        long job = approved().path("jobId").asLong();
        jdbc.update("UPDATE learning_hub.course_generation_steps SET attempts = ? "
            + "WHERE job_id = ? AND kind = 'lesson' AND item_key = 'c1l1'", CourseDraftService.MAX_STEP_ATTEMPTS, job);

        api.error(INSTRUCTOR, api.post(Map.of(), path(job, "/lessons/c1l1/regenerate")), "CORE-VALIDATION_FAILED");
        // the other lesson still can
        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/lessons/c1l2/regenerate")));
    }

    @Test
    void nobodyButTheOwnerCanChangeOrDraftAJob() throws Exception {
        long job = approved().path("jobId").asLong();
        String other = "CORE-RESOURCE_NOT_FOUND";

        api.error(ADMIN, api.put(Map.of("title", "x"), path(job, "/outline")), other);
        api.error(ADMIN, api.post(Map.of(), path(job, "/outline/approve")), other);
        api.error(ADMIN, api.post(Map.of(), path(job, "/lessons/c1l1/regenerate")), other);
        api.error(ADMIN, api.put(Map.of("decision", "accepted"), path(job, "/questions/c1l1/0")), other);
        api.error(ADMIN, api.post(Map.of(), path(job, "/retry")), other);
        api.error(ADMIN, api.post(Map.of(), path(job, "/draft")), other);
        api.error(ADMIN, api.post(Map.of(), path(job, "/cancel")), other);
        api.error(ADMIN, ApiClient.delete(JOBS + "/{id}", job), other);

        api.error(ADMIN, api.post(Map.of(), path(job, "/stop")), other);
    }

    @Test
    void afterTheJobFinishedALessonCanStillBeRegeneratedButThereIsNothingToRetry() throws Exception {
        long job = approved().path("jobId").asLong();
        assertThat(api.ok(INSTRUCTOR, get(path(job, "/content"))).path("status").asText()).isEqualTo("completed");

        api.error(INSTRUCTOR, api.post(Map.of(), path(job, "/retry")), "CORE-VALIDATION_FAILED");
        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/lessons/c1l1/regenerate")));
    }

    @Test
    void regeneratingOneLessonChangesOnlyThatLesson() throws Exception {
        JsonNode before = approved();
        long job = before.path("jobId").asLong();
        String untouched = before.path("lessons").get(1).toString();
        String rewritten = before.path("lessons").get(0).toString();

        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/lessons/c1l1/regenerate")));
        runAll();

        JsonNode after = api.ok(INSTRUCTOR, get(path(job, "/content")));
        assertThat(after.path("lessons").get(1).toString()).isEqualTo(untouched);
        assertThat(after.path("lessons").get(0).toString()).isNotEqualTo(rewritten);
    }

    @Test
    void invalidModelJsonIsAskedThreeTimesThenFailsReadablyAndRetryResumes() throws Exception {
        long job = startJob();
        runAll();
        badLessons = true;
        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/outline/approve")));
        runAll();

        JsonNode failed = api.ok(INSTRUCTOR, get(path(job, "/content")));
        assertThat(failed.path("status").asText()).isEqualTo("failed");
        assertThat(failed.path("error").asText()).contains("không hợp lệ");
        assertThat(badAnswers.get()).isEqualTo(3);

        badLessons = false;
        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/retry")));
        runAll();
        assertThat(api.ok(INSTRUCTOR, get(path(job, "/content"))).path("status").asText())
            .isEqualTo("completed");
    }

    @Test
    void theDraftIsUnpublishedAiGeneratedAndOnlyAcceptedQuestionsReachTheBank() throws Exception {
        long job = approved().path("jobId").asLong();
        int questionsBefore = jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions", Integer.class);

        api.ok(INSTRUCTOR, api.put(Map.of("decision", "accepted"), path(job, "/questions/c1l1/0")));
        api.ok(INSTRUCTOR, api.put(Map.of("decision", "rejected"), path(job, "/questions/c1l1/1")));

        JsonNode done = api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/draft")));
        long courseId = done.path("createdDraftId").asLong();
        assertThat(done.path("status").asText()).isEqualTo("completed");
        assertThat(jdbc.queryForMap("SELECT state, ai_generated FROM learning_hub.course_revisions "
            + "WHERE course_id = ?", courseId)).containsEntry("state", "draft").containsEntry("ai_generated", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions", Integer.class))
            .isEqualTo(questionsBefore + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions WHERE content = 'Câu 2?'",
            Integer.class)).isZero();

        // asking again returns the same draft and writes nothing more
        assertThat(api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/draft"))).path("createdDraftId").asLong())
            .isEqualTo(courseId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions", Integer.class))
            .isEqualTo(questionsBefore + 1);
    }

    @Test
    void acceptingAProposalBanksItAtOnceAndBothDecisionsSurviveAReload() throws Exception {
        long job = approved().path("jobId").asLong();
        int before = jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions", Integer.class);

        api.ok(INSTRUCTOR, api.put(Map.of("decision", "accepted"), path(job, "/questions/c1l1/0")));
        api.ok(INSTRUCTOR, api.put(Map.of("decision", "accepted"), path(job, "/questions/c1l1/0")));
        api.ok(INSTRUCTOR, api.put(Map.of("decision", "rejected"), path(job, "/questions/c1l1/1")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions", Integer.class))
            .isEqualTo(before + 1);
        JsonNode proposals = api.ok(INSTRUCTOR, get(path(job, "/content"))).path("questions").get(0)
            .path("questions");
        assertThat(proposals.get(0).path("decision").asText()).isEqualTo("added");
        assertThat(proposals.get(1).path("decision").asText()).isEqualTo("rejected");
        api.error(INSTRUCTOR, api.put(Map.of("decision", "rejected"), path(job, "/questions/c1l1/0")),
            "CORE-VALIDATION_FAILED");
    }

    @Test
    void askingForQuestionsToBeBankedBeforeAnyWereProposedWritesNothing() throws Exception {
        long job = startJob();
        runAll();
        int before = jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions", Integer.class);

        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/draft")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions", Integer.class)).isEqualTo(before);
    }

    // ---- ticket 01: the job owns a draft course ---------------------------------------------------------------

    private Map<String, Object> jobRow(long job) {
        return jdbc.queryForMap("SELECT course_id, revision_id, status FROM "
            + "learning_hub.course_generation_jobs WHERE job_id = ?", job);
    }

    private static final String LESSONS_OF_REVISION = "FROM learning_hub.course_lessons l JOIN "
        + "learning_hub.course_chapters c ON c.chapter_id = l.chapter_id WHERE c.revision_id = ?";

    @Test
    void creatingAJobCreatesTheDraftCourseAndItsAiGeneratedRevisionWithIt() throws Exception {
        long job = startJob();

        Map<String, Object> row = jobRow(job);
        assertThat(((Number) row.get("course_id")).longValue()).isPositive();
        assertThat(jdbc.queryForMap("SELECT state, ai_generated FROM learning_hub.course_revisions "
            + "WHERE revision_id = ?", row.get("revision_id")))
            .containsEntry("state", "draft").containsEntry("ai_generated", true);
        assertThat(api.ok(INSTRUCTOR, get(path(job, ""))).path("courseId").asLong())
            .isEqualTo(((Number) row.get("course_id")).longValue());
    }

    @Test
    void aJobThatFailsToStartLeavesNeitherAJobNorACourse() throws Exception {
        int courses = jdbc.queryForObject("SELECT count(*) FROM learning_hub.courses", Integer.class);
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("targetLearners", "Giao dịch viên");
        inputs.put("level", "BEGINNER");
        inputs.put("chapters", 1);
        inputs.put("programmeId", -1);
        inputs.put("competencyId", competency);

        api.error(INSTRUCTOR, multipart(JOBS)
            .file(new MockMultipartFile("file", "q.md", "text/markdown", "# Q".getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("inputs", "", "application/json", json.writeValueAsBytes(inputs))),
            "CORE-VALIDATION_FAILED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.courses", Integer.class)).isEqualTo(courses);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_generation_jobs", Integer.class))
            .isZero();
    }

    @Test
    void theOutlineAndLessonsAreWrittenIntoTheRevisionAndAUserEditedLessonIsNeverOverwritten() throws Exception {
        long job = startJob();
        runAll();
        Object revision = jobRow(job).get("revision_id");
        assertThat(jdbc.queryForObject("SELECT count(*) " + LESSONS_OF_REVISION + " AND l.ai_state = 'pending'",
            Integer.class, revision)).isEqualTo(2);

        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/outline/approve")));
        // the author edits the first lesson before the job reaches it
        jdbc.update("UPDATE learning_hub.course_lessons SET ai_state = 'user_edited' WHERE lesson_id = "
            + "(SELECT min(l.lesson_id) " + LESSONS_OF_REVISION + ")", revision);
        runAll();

        assertThat(jdbc.queryForList("SELECT l.ai_state " + LESSONS_OF_REVISION + " ORDER BY c.sort_no, l.sort_no",
            String.class, revision)).containsExactly("user_edited", "written");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.lesson_blocks b JOIN "
            + "learning_hub.course_lessons l ON l.lesson_id = b.lesson_id JOIN learning_hub.course_chapters c "
            + "ON c.chapter_id = l.chapter_id WHERE c.revision_id = ?", Integer.class, revision)).isEqualTo(1);
    }

    @Test
    void stopKeepsTheJobFinishedStateOfItsLessonsAndFinishedJobsCannotBeStopped() throws Exception {
        long job = startJob();
        runAll();
        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/outline/approve")));
        Object revision = jobRow(job).get("revision_id");

        api.ok(INSTRUCTOR, api.post(Map.of(), path(job, "/stop")));

        assertThat(jobRow(job).get("status")).isEqualTo("stopped");
        assertThat(jdbc.queryForObject("SELECT count(*) " + LESSONS_OF_REVISION + " AND l.ai_state = 'pending'",
            Integer.class, revision)).isEqualTo(2);
        api.error(INSTRUCTOR, api.post(Map.of(), path(job, "/stop")), "CORE-VALIDATION_FAILED");
    }

    @Test
    void sourceNotesAreOwnerOnly() throws Exception {
        long job = approved().path("jobId").asLong();

        assertThat(api.ok(INSTRUCTOR, get(path(job, "/content"))).path("lessons").get(0).path("sourceNotes"))
            .hasSize(1);
        api.error(ADMIN, get(path(job, "/content")), "CORE-RESOURCE_NOT_FOUND");
    }
}
