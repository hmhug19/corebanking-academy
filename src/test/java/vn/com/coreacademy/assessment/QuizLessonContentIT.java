package vn.com.coreacademy.assessment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** Lưu, xuất bản, tạo bản nháp và sao chép khóa có bài học loại quiz (trỏ tới đề của chính khóa). */
class QuizLessonContentIT extends AssessmentIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private long competency;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        competency = fixtures.competency();
    }

    private long quiz(Course course, boolean publish) {
        long single = fixtures.question(course.id(), competency, "SINGLE", 0);
        long multiple = fixtures.question(course.id(), competency, "MULTIPLE", 0, 2);
        return fixtures.quiz(quizBody(course.id()), List.of(single, multiple), publish);
    }

    private JsonNode newDraft(Course course) {
        return api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/courses/{c}/draft", course.id()));
    }

    private Map<String, Object> textLesson() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("blockType", "text");
        block.put("title", "Giới thiệu");
        block.put("body", "Nội dung bài học.");
        block.put("bodyFormat", "markdown");
        block.put("completionMode", "manual");
        block.put("completionThreshold", null);
        block.put("isRequired", true);
        block.put("assets", List.of());
        block.put("steps", List.of());
        Map<String, Object> lesson = new LinkedHashMap<>();
        lesson.put("title", "Bài nội dung");
        lesson.put("lessonType", "content");
        lesson.put("estimatedDurationSeconds", 300);
        lesson.put("isRequired", true);
        lesson.put("quizId", null);
        lesson.put("blocks", List.of(block));
        return lesson;
    }

    private Map<String, Object> quizLesson(Long quizId, List<Object> blocks) {
        Map<String, Object> lesson = new LinkedHashMap<>();
        lesson.put("title", "Bài kiểm tra");
        lesson.put("lessonType", "quiz");
        lesson.put("estimatedDurationSeconds", 600);
        lesson.put("isRequired", true);
        lesson.put("quizId", quizId);
        lesson.put("blocks", blocks);
        return lesson;
    }

    private Map<String, Object> draftBody(JsonNode detail, List<Map<String, Object>> lessons) {
        JsonNode revision = detail.path("revision");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", revision.path("rowVersion").asLong());
        body.put("programId", revision.path("programId").asLong());
        body.put("moduleId", revision.path("moduleId").asLong());
        body.put("title", revision.path("title").asText());
        body.put("summary", "Khóa kiểm thử bài học quiz.");
        body.put("level", "basic");
        body.put("defaultRequirement", "optional");
        body.put("coverAssetId", null);
        body.put("coverIcon", null);
        body.put("coverColor", null);
        body.put("objectives", List.of("Mục tiêu"));
        body.put("chapters", List.of(Map.of("title", "Chương 1", "lessons", new ArrayList<>(lessons))));
        body.put("rewardPoints", 0);
        return body;
    }

    private JsonNode save(JsonNode detail, Course course, List<Map<String, Object>> lessons) {
        return api.ok(INSTRUCTOR, api.put(draftBody(detail, lessons), "/api/v1/courses/{c}/revisions/{r}",
            course.id(), detail.path("revision").path("id").asLong()));
    }

    private JsonNode publish(JsonNode saved, Course course) {
        return api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", saved.path("revision").path("rowVersion").asLong()),
            "/api/v1/courses/{c}/revisions/{r}/publish", course.id(), saved.path("revision").path("id").asLong()));
    }

    private JsonNode quizLessonOf(JsonNode detail) {
        for (JsonNode lesson : detail.path("chapters").get(0).path("lessons")) {
            if ("quiz".equals(lesson.path("lesson").path("lessonType").asText())) {
                return lesson;
            }
        }
        throw new AssertionError("Không có bài học loại quiz: " + detail.toPrettyString());
    }

    @Test
    void savesQuizLessonWithQuizOfTheSameCourse() {
        Course course = fixtures.course(LEARNER);
        long quizId = quiz(course, true);
        JsonNode draft = newDraft(course);

        JsonNode saved = save(draft, course, List.of(textLesson(), quizLesson(quizId, List.of())));

        JsonNode lesson = quizLessonOf(saved);
        assertThat(lesson.path("lesson").path("quizId").asLong()).isEqualTo(quizId);
        assertThat(lesson.path("blocks")).isEmpty();
    }

    @Test
    void draftMayHaveQuizLessonWithoutQuizButCannotBePublished() {
        Course course = fixtures.course(LEARNER);
        JsonNode draft = newDraft(course);

        JsonNode saved = save(draft, course, List.of(textLesson(), quizLesson(null, List.of())));

        assertThat(quizLessonOf(saved).path("lesson").path("quizId").isNull()).isTrue();
        api.error(INSTRUCTOR, api.post(Map.of("expectedVersion", saved.path("revision").path("rowVersion").asLong()),
            "/api/v1/courses/{c}/revisions/{r}/publish", course.id(), saved.path("revision").path("id").asLong()),
            "LEARNING-QUIZ_LESSON_UNLINKED");
    }

    @Test
    void rejectsQuizOfAnotherCourse() {
        Course course = fixtures.course(LEARNER);
        Course other = fixtures.course(LEARNER);
        long foreignQuiz = quiz(other, true);
        JsonNode draft = newDraft(course);

        api.error(INSTRUCTOR, api.put(draftBody(draft, List.of(textLesson(), quizLesson(foreignQuiz, List.of()))),
            "/api/v1/courses/{c}/revisions/{r}", course.id(), draft.path("revision").path("id").asLong()),
            "LEARNING-QUIZ_INVALID");
    }

    @Test
    void rejectsMissingAndDeletedQuiz() {
        Course course = fixtures.course(LEARNER);
        long deleted = quiz(course, false);
        api.ok(INSTRUCTOR, ApiClient.delete("/api/v1/quizzes/{id}?expectedVersion={v}", deleted,
            fixtures.quizVersion(deleted)));
        JsonNode draft = newDraft(course);
        String path = "/api/v1/courses/{c}/revisions/{r}";
        long revision = draft.path("revision").path("id").asLong();

        api.error(INSTRUCTOR, api.put(draftBody(draft, List.of(textLesson(), quizLesson(deleted, List.of()))),
            path, course.id(), revision), "LEARNING-QUIZ_INVALID");
        api.error(INSTRUCTOR, api.put(draftBody(draft, List.of(textLesson(), quizLesson(987654321L, List.of()))),
            path, course.id(), revision), "LEARNING-QUIZ_INVALID");
    }

    @Test
    void rejectsQuizLessonWithBlocksAndContentLessonWithQuiz() {
        Course course = fixtures.course(LEARNER);
        long quizId = quiz(course, true);
        JsonNode draft = newDraft(course);
        String path = "/api/v1/courses/{c}/revisions/{r}";
        long revision = draft.path("revision").path("id").asLong();

        List<Object> blocks = List.of(((List<?>) textLesson().get("blocks")).get(0));
        api.error(INSTRUCTOR, api.put(draftBody(draft, List.of(quizLesson(quizId, blocks))), path, course.id(),
            revision), "CORE-VALIDATION_FAILED");

        Map<String, Object> content = textLesson();
        content.put("quizId", quizId);
        api.error(INSTRUCTOR, api.put(draftBody(draft, List.of(content)), path, course.id(), revision),
            "LEARNING-QUIZ_INVALID");
    }

    @Test
    void publishRequiresPublishedQuizThenKeepsLinkInDraftAndClearsItInCopy() {
        Course course = fixtures.course(LEARNER);
        long draftQuiz = quiz(course, false);
        JsonNode draft = newDraft(course);

        JsonNode saved = save(draft, course, List.of(textLesson(), quizLesson(draftQuiz, List.of())));
        api.error(INSTRUCTOR, api.post(Map.of("expectedVersion", saved.path("revision").path("rowVersion").asLong()),
            "/api/v1/courses/{c}/revisions/{r}/publish", course.id(), saved.path("revision").path("id").asLong()),
            "LEARNING-QUIZ_LESSON_UNLINKED");

        long quizId = quiz(course, true);
        JsonNode relinked = save(saved, course, List.of(textLesson(), quizLesson(quizId, List.of())));
        JsonNode published = publish(relinked, course);
        assertThat(published.path("revision").path("state").asText()).isEqualTo("published");
        assertThat(quizLessonOf(published).path("lesson").path("quizId").asLong()).isEqualTo(quizId);

        JsonNode nextDraft = newDraft(course);
        assertThat(nextDraft.path("revision").path("state").asText()).isEqualTo("draft");
        assertThat(quizLessonOf(nextDraft).path("lesson").path("quizId").asLong()).isEqualTo(quizId);

        JsonNode copy = api.ok(INSTRUCTOR, api.post(Map.of("code", AssessmentFixtures.unique("COPY")),
            "/api/v1/courses/{c}/copy", course.id()));
        assertThat(quizLessonOf(copy).path("lesson").path("lessonType").asText()).isEqualTo("quiz");
        assertThat(quizLessonOf(copy).path("lesson").path("quizId").isNull()).isTrue();
    }
}
