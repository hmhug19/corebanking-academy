package vn.com.coreacademy.learning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.learning.config.LearningPointsProperties;
import vn.com.coreacademy.learning.dto.GamificationConfig;
import vn.com.coreacademy.learning.service.XpLedgerService;
import vn.com.coreacademy.security.SecurityConstants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/**
 * Sổ XP ghi khi bài học xong, đạt đề và xong khóa. Các ca dùng luồng thật của học viên (phiên học, nộp bài) để chứng
 * minh XP được ghi cùng giao dịch với sự kiện; các ca về trọng số và khóa duy nhất gọi thẳng service với mã người giả.
 */
class XpLedgerIT extends AssessmentIntegrationTest {
    private static final String ME = "/api/v1/me";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private XpLedgerService xp;

    @Autowired
    private LearningPointsProperties seed;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private long competency;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        competency = fixtures.competency();
        xp.currentConfig();
    }

    /** Phiên bản cấu hình do test thêm bị gỡ để ca khác thấy lại phiên bản 1; dòng sổ không có khóa ngoại tới đây. */
    @AfterEach
    void dropTestVersions() {
        jdbc.update("delete from learning_hub.gamification_config_versions where version_no > 1");
    }

    private record Setup(Course course, long quizId, long quizLessonId, long enrollmentId) {
    }

    private Setup setup() {
        Course course = fixtures.course(LEARNER);
        long single = fixtures.question(course.id(), competency, "SINGLE", 0);
        long multiple = fixtures.question(course.id(), competency, "MULTIPLE", 0, 2);
        long quizId = fixtures.quiz(quizBody(course.id()), List.of(single, multiple), true);
        long chapterId = jdbc.queryForObject("SELECT chapter_id FROM learning_hub.course_lessons WHERE lesson_id = ?",
            Long.class, course.lessonId());
        long quizLessonId = jdbc.queryForObject("""
            INSERT INTO learning_hub.course_lessons (chapter_id, title, lesson_type, estimated_duration_seconds,
                is_required, sort_no, quiz_id, created_by, updated_by)
            VALUES (?, 'Bài kiểm tra cuối', 'quiz', 600, true, 99, ?, ?, ?)
            RETURNING lesson_id
            """, Long.class, chapterId, quizId, INSTRUCTOR, INSTRUCTOR);
        long enrollmentId = jdbc.queryForObject("""
            SELECT enrollment_id FROM learning_hub.course_enrollments WHERE course_id = ? AND user_id = ?
            """, Long.class, course.id(), LEARNER);
        return new Setup(course, quizId, quizLessonId, enrollmentId);
    }

    private void completeContentThroughApi(Setup s) {
        long blockId = jdbc.queryForObject("SELECT block_id FROM learning_hub.lesson_blocks WHERE lesson_id = ?",
            Long.class, s.course().lessonId());
        JsonNode session = learningCall(api.post(Map.of("enrollmentId", s.enrollmentId(), "blockId", blockId,
            "takeover", true), "/api/v1/me/learning/sessions"));
        learningCall(api.put(Map.of("sessionId", session.path("sessionId").asText(),
            "sessionFence", session.path("sessionFence").asLong(), "expectedVersion",
            session.path("expectedVersion").asLong(), "sequence", session.path("lastAcceptedSequence").asLong() + 1,
            "eventType", "CLOSE", "complete", true),
            "/api/v1/me/learning/enrollments/{e}/blocks/{b}/progress", s.enrollmentId(), blockId));
    }

    /** Cookie media của phiên học yêu cầu claim auth-version như access token thật. */
    private JsonNode learningCall(MockHttpServletRequestBuilder request) {
        var response = api.raw(null, request.with(jwt().jwt(token -> token.subject(LEARNER)
            .claim(SecurityConstants.AUTH_VERSION_CLAIM, 0L))));
        try {
            JsonNode body = json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertThat(response.getStatus()).as(body.toPrettyString()).isEqualTo(200);
            return body.path("data");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Làm một lượt; correct = chọn đáp án đúng ở câu đầu (đạt đúng 50%), ngược lại bỏ trống. */
    private JsonNode attempt(Setup s, boolean correct) {
        JsonNode session = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", s.quizId()));
        if (correct) {
            JsonNode question = session.path("questions").get(0);
            api.ok(LEARNER, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(0).path("id")
                .asLong())), ME + "/quiz-attempts/{a}/questions/{q}", session.path("attemptId").asLong(),
                question.path("attemptQuestionId").asLong()));
        }
        return api.ok(LEARNER, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit",
            session.path("attemptId").asLong()));
    }

    private List<Map<String, Object>> rows(String user, String kind, Object ref) {
        return jdbc.queryForList("""
            select amount, source_kind, source_ref, config_version_id, occurred_at
            from learning_hub.xp_ledger where user_id = ? and source_kind = ? and source_ref = ?
            """, user, kind, String.valueOf(ref));
    }

    private long addVersion(int lessonXp, int firstTryThreshold) {
        return jdbc.queryForObject("""
            insert into learning_hub.gamification_config_versions (version_no, lesson_xp, quiz_pass_xp,
                first_try_bonus_xp, first_try_threshold_percent, community_xp, community_daily_cap)
            values ((select max(version_no) + 1 from learning_hub.gamification_config_versions), ?, 30, 20, ?, 10, 30)
            returning version_id
            """, Long.class, lessonXp, firstTryThreshold);
    }

    @Test
    void versionOneIsSeededFromTheCurrentPointSettings() {
        GamificationConfig config = xp.currentConfig();

        assertThat(config.versionNo()).isEqualTo(1);
        assertThat(config.lessonXp()).isEqualTo(seed.getLesson()).isEqualTo(5);
        assertThat(config.quizPassXp()).isEqualTo(seed.getQuizPass()).isEqualTo(30);
        assertThat(config.firstTryBonusXp()).isEqualTo(20);
        assertThat(config.firstTryThresholdPercent()).isEqualTo(90);
    }

    @Test
    void completingALessonWritesOneRowInTheSameTransactionNamingTheVersion() {
        Setup s = setup();

        completeContentThroughApi(s);

        List<Map<String, Object>> lesson = rows(LEARNER, "LESSON", s.course().lessonId());
        assertThat(lesson).hasSize(1);
        assertThat(((Number) lesson.get(0).get("amount")).intValue()).isEqualTo(5);
        assertThat(((Number) lesson.get(0).get("config_version_id")).longValue())
            .isEqualTo(xp.currentConfig().versionId());
        assertThat(lesson.get(0).get("occurred_at")).isNotNull();
    }

    @Test
    void repeatingTheSameSourceWritesNoSecondRow() {
        String user = "XP-REPEAT";
        Instant at = Instant.parse("2026-11-02T03:00:00Z");

        xp.lessonCompleted(user, 9001L, at);
        xp.lessonCompleted(user, 9001L, at.plusSeconds(60));
        xp.courseCompleted(user, 77L, 40, at);
        xp.courseCompleted(user, 77L, 40, at.plusSeconds(60));

        assertThat(rows(user, "LESSON", 9001L)).hasSize(1);
        assertThat(rows(user, "COURSE", 77L)).hasSize(1);
        assertThat(((Number) rows(user, "COURSE", 77L).get(0).get("amount")).intValue()).isEqualTo(40);
        assertThat(rows(user, "LESSON", 9001L).get(0).get("occurred_at")).as("the first occurrence is kept")
            .isEqualTo(java.sql.Timestamp.from(at));
    }

    @Test
    void redoingAQuizPassWritesNoSecondRow() {
        Setup s = setup();
        jdbc.update("update learning_hub.quizzes set allow_retake_after_pass = true where quiz_id = ?", s.quizId());

        attempt(s, true);
        attempt(s, true);

        assertThat(rows(LEARNER, "QUIZ_PASS", s.quizId())).hasSize(1);
        assertThat(((Number) rows(LEARNER, "QUIZ_PASS", s.quizId()).get(0).get("amount")).intValue()).isEqualTo(30);
    }

    @Test
    void aFailedAttemptEarnsNothing() {
        Setup s = setup();

        attempt(s, false);

        assertThat(rows(LEARNER, "QUIZ_PASS", s.quizId())).isEmpty();
        assertThat(rows(LEARNER, "QUIZ_FIRST_TRY_BONUS", s.quizId())).isEmpty();
    }

    @Test
    void theFirstTryBonusNeedsTheFirstAttemptAtOrAboveTheThreshold() {
        Setup below = setup();
        attempt(below, true);
        assertThat(rows(LEARNER, "QUIZ_PASS", below.quizId())).as("a pass of 50% is below 90%").hasSize(1);
        assertThat(rows(LEARNER, "QUIZ_FIRST_TRY_BONUS", below.quizId())).isEmpty();

        addVersion(5, 50);
        Setup atThreshold = setup();
        attempt(atThreshold, true);
        List<Map<String, Object>> bonus = rows(LEARNER, "QUIZ_FIRST_TRY_BONUS", atThreshold.quizId());
        assertThat(bonus).as("exactly at the threshold counts").hasSize(1);
        assertThat(((Number) bonus.get(0).get("amount")).intValue()).isEqualTo(20);

        Setup second = setup();
        attempt(second, false);
        attempt(second, true);
        assertThat(rows(LEARNER, "QUIZ_PASS", second.quizId())).hasSize(1);
        assertThat(rows(LEARNER, "QUIZ_FIRST_TRY_BONUS", second.quizId()))
            .as("a pass on the second attempt gets no bonus").isEmpty();
    }

    @Test
    void completingACourseWritesItsOwnRewardOnce() {
        Setup s = setup();
        jdbc.update("update learning_hub.course_completion_rules set reward_points = 40 where revision_id = ?",
            s.course().revisionId());

        completeContentThroughApi(s);
        assertThat(rows(LEARNER, "COURSE", s.course().id())).as("quiz still pending").isEmpty();
        attempt(s, true);

        List<Map<String, Object>> course = rows(LEARNER, "COURSE", s.course().id());
        assertThat(course).hasSize(1);
        assertThat(((Number) course.get(0).get("amount")).intValue()).isEqualTo(40);
        assertThat(rows(LEARNER, "LESSON", s.quizLessonId())).as("the quiz lesson also completes").hasSize(1);
    }

    @Test
    void aChangedWeightAppliesOnlyToLaterRows() {
        String user = "XP-FORWARD";
        xp.lessonCompleted(user, 9101L, Instant.now());
        long second = addVersion(7, 90);

        xp.lessonCompleted(user, 9102L, Instant.now());

        assertThat(((Number) rows(user, "LESSON", 9101L).get(0).get("amount")).intValue()).isEqualTo(5);
        assertThat(((Number) rows(user, "LESSON", 9102L).get(0).get("amount")).intValue()).isEqualTo(7);
        assertThat(((Number) rows(user, "LESSON", 9102L).get(0).get("config_version_id")).longValue())
            .isEqualTo(second);
    }

    @Test
    void ledgerRowsCanNeverBeUpdatedOrDeleted() {
        String user = "XP-APPEND-ONLY";
        xp.lessonCompleted(user, 9201L, Instant.now());

        assertThatThrownBy(() -> jdbc.update("update learning_hub.xp_ledger set amount = 999 where user_id = ?", user))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from learning_hub.xp_ledger where user_id = ?", user))
            .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(rows(user, "LESSON", 9201L)).hasSize(1);
    }
}
