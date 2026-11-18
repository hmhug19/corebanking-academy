package vn.com.coreacademy.assessment;

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
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.questionBody;

/** Câu hỏi nhanh cuối bài: nguồn từ flashcard đã công bố, chấm trên server, không đổi kết quả học tập. */
class CheckpointApiIT extends AssessmentIntegrationTest {
    private static final String CHECKPOINT = "/api/v1/me/lessons/{l}/checkpoint";
    private static final String ANSWERS = CHECKPOINT + "/answers";

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

    /** Câu hỏi của bài, đáp án đúng là phương án A (SINGLE) hoặc A và C (MULTIPLE), đã công bố làm flashcard. */
    private long published(Course course, String type, int... correct) {
        Map<String, Object> body = questionBody(course.id(), competency, type, correct);
        body.put("lessonId", course.lessonId());
        long question = api.ok(INSTRUCTOR, api.post(body, "/api/v1/questions")).path("id").asLong();
        long version = api.ok(INSTRUCTOR, get("/api/v1/questions/{id}", question)).findValue("rowVersion").asLong();
        api.ok(INSTRUCTOR, api.put(Map.of("status", "PUBLISHED", "questionVersion", version),
            "/api/v1/courses/{c}/flashcards/{q}", course.id(), question));
        return question;
    }

    private List<Long> optionIds(JsonNode question) {
        List<Long> ids = new java.util.ArrayList<>();
        question.path("options").forEach(option -> ids.add(option.path("id").asLong()));
        return ids;
    }

    private JsonNode answer(String user, Course course, JsonNode question, List<Long> options) {
        return api.ok(user, api.post(Map.of("questionId", question.path("questionId").asLong(),
            "questionVersion", question.path("questionVersion").asLong(), "optionIds", options),
            ANSWERS, course.lessonId()));
    }

    private long rows(long lessonId) {
        return jdbc.queryForObject("select count(*) from learning_hub.checkpoint_answers where lesson_id = ?",
            Long.class, lessonId);
    }

    @Test
    void lessonWithoutPublishedFlashcardsHasEmptyCheckpoint() {
        Course course = fixtures.course(LEARNER);
        long draft = fixtures.question(course.id(), competency, "SINGLE", 0);

        JsonNode view = api.ok(LEARNER, get(CHECKPOINT, course.lessonId()));
        assertThat(view.path("lessonId").asLong()).isEqualTo(course.lessonId());
        assertThat(view.path("questions")).isEmpty();
        assertThat(draft).isPositive();
    }

    @Test
    void showsAtMostThreePublishedQuestionsWithoutCorrectFlags() {
        Course course = fixtures.course(LEARNER);
        for (int i = 0; i < 4; i++) {
            published(course, "SINGLE", 0);
        }
        // Not published, and published for another lesson of nobody: neither may show up.
        fixtures.question(course.id(), competency, "SINGLE", 0);

        JsonNode questions = api.ok(LEARNER, get(CHECKPOINT, course.lessonId())).path("questions");
        assertThat(questions).hasSize(3);
        questions.forEach(question -> {
            assertThat(question.has("correct")).isFalse();
            assertThat(question.path("options")).hasSize(4);
            question.path("options").forEach(option -> {
                assertThat(option.has("correct")).isFalse();
                assertThat(option.has("isCorrect")).isFalse();
            });
            assertThat(question.has("explanation")).isFalse();
        });
    }

    @Test
    void picksUnansweredFirstThenMissedThenTheRest() {
        Course course = fixtures.course(LEARNER);
        for (int i = 0; i < 4; i++) {
            published(course, "SINGLE", 0);
        }
        JsonNode first = api.ok(LEARNER, get(CHECKPOINT, course.lessonId())).path("questions");
        JsonNode missed = first.get(0);
        JsonNode right = first.get(1);
        JsonNode rightToo = first.get(2);
        List<Long> wrong = List.of(optionIds(missed).get(1));
        answer(LEARNER, course, missed, wrong);
        answer(LEARNER, course, right, List.of(optionIds(right).get(0)));
        answer(LEARNER, course, rightToo, List.of(optionIds(rightToo).get(0)));

        JsonNode next = api.ok(LEARNER, get(CHECKPOINT, course.lessonId())).path("questions");
        long unanswered = next.get(0).path("questionId").asLong();
        assertThat(List.of(missed.path("questionId").asLong(), right.path("questionId").asLong(),
            rightToo.path("questionId").asLong())).doesNotContain(unanswered);
        assertThat(next.get(1).path("questionId").asLong()).isEqualTo(missed.path("questionId").asLong());
        assertThat(next).hasSize(3);
    }

    @Test
    void gradesOnTheServerAndRecordsOneRowPerAnswer() {
        Course course = fixtures.course(LEARNER);
        published(course, "MULTIPLE", 0, 2);
        JsonNode question = api.ok(LEARNER, get(CHECKPOINT, course.lessonId())).path("questions").get(0);
        List<Long> options = optionIds(question);
        JsonNode competencyBefore = api.ok(LEARNER, get("/api/v1/me/competency"));

        JsonNode partial = answer(LEARNER, course, question, List.of(options.get(0)));
        assertThat(partial.path("correct").asBoolean()).isFalse();
        assertThat(partial.path("correctOptionIds")).extracting(JsonNode::asLong)
            .containsExactlyInAnyOrder(options.get(0), options.get(2));
        assertThat(partial.path("explanation").asText()).isEqualTo("Giải thích đáp án");

        JsonNode full = answer(LEARNER, course, question, List.of(options.get(2), options.get(0)));
        assertThat(full.path("correct").asBoolean()).isTrue();

        assertThat(rows(course.lessonId())).isEqualTo(2);
        assertThat(api.ok(LEARNER, get("/api/v1/me/competency"))).isEqualTo(competencyBefore);
    }

    @Test
    void refusesAnAnswerWhenTheQuestionChangedOrLeftTheLesson() {
        Course course = fixtures.course(LEARNER);
        long question = published(course, "SINGLE", 0);
        JsonNode shown = api.ok(LEARNER, get(CHECKPOINT, course.lessonId())).path("questions").get(0);
        List<Long> options = optionIds(shown);

        jdbc.update("update learning_hub.questions set row_version = row_version + 1 where question_id = ?", question);
        api.error(LEARNER, api.post(Map.of("questionId", question, "questionVersion",
            shown.path("questionVersion").asLong(), "optionIds", List.of(options.get(0))), ANSWERS,
            course.lessonId()), "ASSESSMENT-CHECKPOINT_STALE");

        Course other = fixtures.course(LEARNER);
        api.error(LEARNER, api.post(Map.of("questionId", question, "questionVersion",
            shown.path("questionVersion").asLong() + 1, "optionIds", List.of(options.get(0))), ANSWERS,
            other.lessonId()), "ASSESSMENT-CHECKPOINT_STALE");
        assertThat(rows(course.lessonId())).isZero();
    }

    @Test
    void refusesInvalidSelections() {
        Course course = fixtures.course(LEARNER);
        published(course, "SINGLE", 0);
        JsonNode question = api.ok(LEARNER, get(CHECKPOINT, course.lessonId())).path("questions").get(0);
        List<Long> options = optionIds(question);

        api.error(LEARNER, api.post(Map.of("questionId", question.path("questionId").asLong(), "questionVersion",
            question.path("questionVersion").asLong(), "optionIds", List.of(options.get(0), options.get(1))),
            ANSWERS, course.lessonId()), "CORE-VALIDATION_FAILED");
        api.error(LEARNER, api.post(Map.of("questionId", question.path("questionId").asLong(), "questionVersion",
            question.path("questionVersion").asLong(), "optionIds", List.of(-5L)), ANSWERS, course.lessonId()),
            "CORE-VALIDATION_FAILED");
        api.error(LEARNER, api.post(Map.of("questionId", question.path("questionId").asLong(), "questionVersion",
            question.path("questionVersion").asLong(), "optionIds", List.of()), ANSWERS, course.lessonId()),
            "CORE-VALIDATION_FAILED");
        assertThat(rows(course.lessonId())).isZero();
    }

    @Test
    void refusesACallerWhoCannotReadTheLesson() {
        Course course = fixtures.course(LEARNER);
        published(course, "SINGLE", 0);
        JsonNode question = api.ok(LEARNER, get(CHECKPOINT, course.lessonId())).path("questions").get(0);

        api.error(LEARNER_2, get(CHECKPOINT, course.lessonId()), "LEARNING-NOT_FOUND");
        api.error(LEARNER_2, api.post(Map.of("questionId", question.path("questionId").asLong(), "questionVersion",
            question.path("questionVersion").asLong(), "optionIds", List.of(optionIds(question).get(0))), ANSWERS,
            course.lessonId()), "LEARNING-NOT_FOUND");
        assertThat(rows(course.lessonId())).isZero();
    }
}
