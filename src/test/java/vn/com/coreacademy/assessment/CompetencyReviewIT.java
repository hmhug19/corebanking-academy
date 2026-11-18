package vn.com.coreacademy.assessment;

import java.util.ArrayList;
import java.util.HashMap;
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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** Gợi ý ôn tập: năng lực yếu của chính người gọi và các bài mà câu sai của họ đến từ. */
class CompetencyReviewIT extends AssessmentIntegrationTest {
    private static final String REVIEW = "/api/v1/me/competency/review";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private Course home;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        home = fixtures.course(LEARNER, LEARNER_2);
    }

    /** Một đề trong khóa {@code home}; mỗi câu hỏi gắn với bài tương ứng (null = không gắn bài). */
    private long submit(String learner, long competency, List<Long> lessons) {
        List<Long> questions = new ArrayList<>();
        for (Long lesson : lessons) {
            long question = fixtures.question(home.id(), competency, "SINGLE", 0);
            if (lesson != null) {
                jdbc.update("UPDATE learning_hub.questions SET lesson_id = ? WHERE question_id = ?", lesson, question);
            }
            questions.add(question);
        }
        Map<String, Object> body = new HashMap<>(quizBody(home.id()));
        body.put("questionCount", questions.size());
        long quizId = fixtures.quiz(body, questions, true);
        long attemptId = api.ok(learner, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId))
            .path("attemptId").asLong();
        // No answer given: every question is missed.
        api.ok(learner, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", attemptId));

        return attemptId;
    }

    /** Sets what the competency adds up to for this attempt, e.g. 7 of 10 is 70%. */
    private void score(long attemptId, long competency, double earned, double max) {
        jdbc.update("""
            UPDATE learning_hub.attempt_competency_scores SET earned_score = ?, max_score = ?
            WHERE attempt_id = ? AND competency_id = ?
            """, earned, max, attemptId, competency);
    }

    private void checkpoint(String user, long lesson, long competency, boolean correct, int daysAgo) {
        jdbc.update("""
            INSERT INTO learning_hub.checkpoint_answers
                (user_id, lesson_id, question_id, question_version, competency_id, correct, answered_at)
            VALUES (?, ?, 1, 1, ?, ?, now() - make_interval(days => ?))
            """, user, lesson, competency, correct, daysAgo);
    }

    private JsonNode review(String user, long competency) {
        return api.ok(user, get(REVIEW + "?competencyId={c}", competency));
    }

    private static List<Long> lessonIds(JsonNode entry) {
        List<Long> ids = new ArrayList<>();
        entry.path("lessons").forEach(lesson -> ids.add(lesson.path("lessonId").asLong()));
        return ids;
    }

    @Test
    void onlyCompetenciesBelowSeventyPercentAreWeak() {
        long weak = fixtures.competency();
        long edge = fixtures.competency();
        long strong = fixtures.competency();
        score(submit(LEARNER, weak, List.of(home.lessonId(), home.lessonId())), weak, 6.9, 10);
        score(submit(LEARNER, edge, List.of(home.lessonId(), home.lessonId())), edge, 7, 10);
        score(submit(LEARNER, strong, List.of(home.lessonId(), home.lessonId())), strong, 10, 10);

        assertThat(review(LEARNER, weak)).hasSize(1);
        assertThat(review(LEARNER, weak).get(0).path("percent").asDouble()).isEqualTo(69.0);
        assertThat(review(LEARNER, edge)).isEmpty();
        assertThat(review(LEARNER, strong)).isEmpty();
        List<Long> all = new ArrayList<>();
        api.ok(LEARNER, get(REVIEW + "?limit=10")).forEach(entry -> all.add(entry.path("competencyId").asLong()));
        assertThat(all).contains(weak).doesNotContain(edge, strong);
    }

    @Test
    void aCompetencyWithNoGradedAttemptHasNoPercentAndIsLeftOut() {
        long untouched = fixtures.competency();

        assertThat(review(LEARNER, untouched)).isEmpty();
    }

    @Test
    void lessonsAreRankedByExamAndCheckpointMissesInsideNinetyDays() {
        long competency = fixtures.competency();
        Course second = fixtures.course(LEARNER);
        Course third = fixtures.course(LEARNER);
        // Exam: first lesson 2 misses, second 1, third 0. Checkpoint: second gets 2 more, third one too old to count.
        submit(LEARNER, competency, List.of(home.lessonId(), home.lessonId(), second.lessonId()));
        checkpoint(LEARNER, second.lessonId(), competency, false, 1);
        checkpoint(LEARNER, second.lessonId(), competency, false, 2);
        checkpoint(LEARNER, third.lessonId(), competency, false, 120);

        JsonNode entry = review(LEARNER, competency).get(0);

        assertThat(lessonIds(entry)).containsExactly(second.lessonId(), home.lessonId());
        assertThat(entry.path("lessons").get(0).path("missedCount").asLong()).isEqualTo(3);
        assertThat(entry.path("lessons").get(1).path("missedCount").asLong()).isEqualTo(2);
        assertThat(entry.path("lessons").get(0).path("courseId").asLong()).isEqualTo(second.id());
        assertThat(entry.path("lessons").get(0).path("lessonTitle").asText()).isNotBlank();
        assertThat(entry.path("lessons").get(0).path("lastMissedAt").asText()).isNotBlank();
    }

    @Test
    void examMissesOlderThanNinetyDaysDoNotCount() {
        long competency = fixtures.competency();
        long attempt = submit(LEARNER, competency, List.of(home.lessonId(), home.lessonId()));
        jdbc.update("UPDATE learning_hub.quiz_attempts SET submitted_at = now() - interval '100 days' "
            + "WHERE attempt_id = ?", attempt);

        JsonNode entry = review(LEARNER, competency).get(0);

        assertThat(entry.path("lessons")).isEmpty();
    }

    @Test
    void twoCorrectCheckpointAnswersInARowTakeTheLessonOut() {
        long competency = fixtures.competency();
        Course mastered = fixtures.course(LEARNER);
        Course recovering = fixtures.course(LEARNER);
        Course slipped = fixtures.course(LEARNER);
        submit(LEARNER, competency, List.of(mastered.lessonId(), recovering.lessonId(), slipped.lessonId()));
        checkpoint(LEARNER, mastered.lessonId(), competency, false, 5);
        checkpoint(LEARNER, mastered.lessonId(), competency, true, 4);
        checkpoint(LEARNER, mastered.lessonId(), competency, true, 3);
        checkpoint(LEARNER, recovering.lessonId(), competency, false, 5);
        checkpoint(LEARNER, recovering.lessonId(), competency, true, 4);
        checkpoint(LEARNER, slipped.lessonId(), competency, true, 5);
        checkpoint(LEARNER, slipped.lessonId(), competency, true, 4);
        checkpoint(LEARNER, slipped.lessonId(), competency, false, 3);

        List<Long> lessons = lessonIds(review(LEARNER, competency).get(0));

        assertThat(lessons).containsExactlyInAnyOrder(recovering.lessonId(), slipped.lessonId())
            .doesNotContain(mastered.lessonId());
    }

    @Test
    void lessonsTheCallerCannotOpenAreLeftOut() {
        long competency = fixtures.competency();
        Course foreign = fixtures.course(LEARNER_2);
        submit(LEARNER, competency, List.of(foreign.lessonId(), home.lessonId()));
        checkpoint(LEARNER, foreign.lessonId(), competency, false, 1);

        List<Long> lessons = lessonIds(review(LEARNER, competency).get(0));

        assertThat(lessons).containsExactly(home.lessonId());
    }

    @Test
    void atMostThreeLessonsPerCompetencyAndLimitCompetenciesOverall() {
        long first = fixtures.competency();
        long second = fixtures.competency();
        List<Long> lessons = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lessons.add(fixtures.course(LEARNER).lessonId());
        }
        score(submit(LEARNER, first, lessons), first, 0, 10);
        score(submit(LEARNER, second, List.of(home.lessonId(), home.lessonId())), second, 1, 10);

        assertThat(lessonIds(review(LEARNER, first).get(0))).hasSize(3);
        JsonNode one = api.ok(LEARNER, get(REVIEW + "?limit=1"));
        assertThat(one).hasSize(1);
        double worst = one.get(0).path("percent").asDouble();
        api.ok(LEARNER, get(REVIEW + "?limit=10")).forEach(entry ->
            assertThat(entry.path("percent").asDouble()).isGreaterThanOrEqualTo(worst));
    }

    @Test
    void onlyTheCallersOwnMissesCount() {
        long competency = fixtures.competency();
        submit(LEARNER, competency, List.of(home.lessonId(), home.lessonId()));
        checkpoint(LEARNER, home.lessonId(), competency, false, 1);

        assertThat(review(LEARNER_2, competency)).isEmpty();
    }
}
