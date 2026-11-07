package vn.com.coreacademy.learning;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.learning.config.LearningPointsProperties;
import vn.com.coreacademy.learning.dto.LearningPointsViews.MyPoints;
import vn.com.coreacademy.learning.dto.LearningPointsViews.PointParts;
import vn.com.coreacademy.learning.service.LearningPointsService;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/**
 * Điểm học tập từng phần trên dữ liệu dựng sẵn. Người trong test là mã giả (bảng không có FK) để mỗi ca có điểm riêng,
 * không lẫn với dữ liệu của các test khác dùng chung database.
 */
class LearningPointsIT extends AssessmentIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private static final LocalDate TODAY_2030 = LocalDate.of(2030, 1, 8);
    private static final AtomicInteger CYCLES = new AtomicInteger(100);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LearningPointsService points;

    @Autowired
    private LearningPointsProperties weights;

    @Autowired
    private DataSource dataSource;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private Course course;
    private List<Long> questions;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        course = fixtures.course(LEARNER);
        long competency = fixtures.competency();
        questions = List.of(fixtures.question(course.id(), competency, "SINGLE", 0),
            fixtures.question(course.id(), competency, "SINGLE", 1));
    }

    @Test
    void theSeedWeightsOfConfigVersionOne() {
        assertThat(weights.getLesson()).isEqualTo(5);
        assertThat(weights.getQuizPass()).isEqualTo(30);
    }

    @Test
    void pointsAreTheLedgerSumsBySource() {
        String user = "PT-LEDGER";
        ledger(user, "LESSON", "a", 5);
        ledger(user, "LESSON", "b", 5);
        ledger(user, "QUIZ_PASS", "q1", 30);
        ledger(user, "QUIZ_FIRST_TRY_BONUS", "q1", 20);
        ledger(user, "COURSE", "c1", 40);

        MyPoints result = points.scores(List.of(user, "PT-NOBODY"), TODAY).get(user);

        assertThat(result.parts()).isEqualTo(new PointParts(10, 30, 20, 40, 0));
        assertThat(result.total()).isEqualTo(100);
        assertThat(points.scores(List.of("PT-NOBODY"), TODAY).get("PT-NOBODY").total()).isZero();
    }

    @Test
    void aLessonThatIsOnlyStartedEarnsNothingAndTheStreakEarnsNoXp() {
        String user = "PT-STARTED";
        long enrollment = enrollment(user, course.id(), course.revisionId(), 1, "in_progress");
        jdbc.update("""
            insert into learning_hub.lesson_progress (enrollment_id, lesson_id, status, progress_percent, created_by,
                updated_by)
            values (?, ?, 'in_progress', 60, ?, ?)
            """, enrollment, course.lessonId(), user, user);
        // Ngày học liên tiếp không sinh XP: chỉ sổ ghi XP mới có điểm.
        activity("PT-DAYS", 3);

        assertThat(points.scores(List.of(user), TODAY).get(user).total()).isZero();
        assertThat(points.scores(List.of("PT-DAYS"), TODAY).get("PT-DAYS").total()).isZero();
    }

    @Test
    void streakDaysAreWorkingDaysAndSkipTheWeekend() {
        // 2030-01-08 là thứ Ba; thứ Sáu 01-04 và thứ Hai 01-07 nối liền qua cuối tuần.
        String user = "PT-STREAK";
        String idle = "PT-IDLE";
        activityOn(user, TODAY_2030, TODAY_2030.minusDays(1), TODAY_2030.minusDays(4));
        activityOn(idle, TODAY_2030.minusDays(3));

        Map<String, MyPoints> result = points.scores(List.of(user, idle, "PT-NONE"), TODAY_2030);

        assertThat(result.get(user).streakDays()).isEqualTo(3);
        assertThat(result.get(idle).streakDays()).as("a working day missed").isZero();
        assertThat(result.get("PT-NONE").streakDays()).isZero();
    }

    @Test
    void theEndpointReturnsTheCallersPointsAndRefusesANonLearner() {
        JsonNode mine = api.ok(LEARNER, get("/api/v1/me/learning/points"));

        assertThat(mine.fieldNames()).toIterable().containsExactly("total", "parts", "streakDays");
        assertThat(mine.path("parts").fieldNames()).toIterable()
            .containsExactly("lessons", "quizzes", "firstTryBonus", "courses", "community");
        assertThat(mine.path("total").asLong()).isEqualTo(mine.path("parts").path("lessons").asLong()
            + mine.path("parts").path("quizzes").asLong() + mine.path("parts").path("firstTryBonus").asLong()
            + mine.path("parts").path("courses").asLong() + mine.path("parts").path("community").asLong());
        api.http(ADMIN, get("/api/v1/me/learning/points"), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void theBackfillReplaysHistoryOnceAndKeepsXpWhenTheSourceIsRemoved() {
        String user = "PT-MIGRATE";
        // Bài học: cùng một bài gốc hoàn thành ở hai lần học chỉ cho 5 XP; một bài khác thêm 5.
        long first = lessonOf(course.revisionId()).get(0);
        lessonDone(enrollment(user, course.id(), course.revisionId(), 1, "completed"), first);
        lessonDone(enrollment(user, course.id(), course.revisionId(), 2, "in_progress"), first);
        long other = insertLesson(course.revisionId());
        lessonDone(enrollment(user, course.id(), course.revisionId(), 3, "in_progress"), other);
        reward(course.revisionId(), 40);
        // Đề đạt lần đầu với 100% được thêm thưởng; đề đạt lần hai thì không; đề chưa xuất bản không tính.
        long firstTry = quiz(true);
        long secondTry = quiz(true);
        long unpublished = quiz(false);
        attempt(user, firstTry, 1, "SUBMITTED", true, 100);
        attempt(user, secondTry, 1, "SUBMITTED", false, 20);
        attempt(user, secondTry, 2, "SUBMITTED", true, 100);
        attempt(user, unpublished, 1, "SUBMITTED", true, 100);

        runBackfill();

        assertThat(parts(user)).isEqualTo(new PointParts(10, 60, 20, 40, 0));
        assertThat(parts(user).lessons() + parts(user).quizzes() + parts(user).firstTryBonus()
            + parts(user).courses()).as("old points without the streak part").isEqualTo(130);
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.xp_ledger where user_id = ?"
            + " and reason = 'migration'", Integer.class, user)).isEqualTo(6);

        runBackfill();
        assertThat(parts(user)).as("a rerun writes nothing twice").isEqualTo(new PointParts(10, 60, 20, 40, 0));

        // Xóa mềm đề, rút khóa và xóa bài không làm mất XP đã có.
        jdbc.update("update learning_hub.quizzes set is_deleted = true where quiz_id = ?", firstTry);
        jdbc.update("update learning_hub.course_enrollments set status = 'withdrawn' where user_id = ?", user);
        assertThat(parts(user)).isEqualTo(new PointParts(10, 60, 20, 40, 0));
    }

    private void runBackfill() {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                "docs/sql/upgrade/2026-11-07-xp-ledger-backfill.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void ledger(String user, String kind, String ref, int amount) {
        jdbc.update("""
            insert into learning_hub.gamification_config_versions (version_no, lesson_xp, quiz_pass_xp,
                first_try_bonus_xp, first_try_threshold_percent, community_xp, community_daily_cap)
            values (1, 5, 30, 20, 90, 10, 30) on conflict (version_no) do nothing
            """);
        jdbc.update("""
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref, config_version_id)
            select ?, ?, now(), ?, ?, version_id from learning_hub.gamification_config_versions where version_no = 1
            """, user, amount, kind, ref);
    }

    private PointParts parts(String user) {
        return points.scores(List.of(user), TODAY).get(user).parts();
    }

    /** Bản nháp mới nhân bản từ bản đã xuất bản rồi xuất bản; trả revisionId. */
    private long publishNewRevision() {
        JsonNode draft = api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/courses/{c}/draft", course.id()));
        long revisionId = draft.path("revision").path("id").asLong();
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", draft.path("revision").path("rowVersion").asLong()),
            "/api/v1/courses/{c}/revisions/{r}/publish", course.id(), revisionId));
        return revisionId;
    }

    private List<Long> lessonOf(long revisionId) {
        return jdbc.queryForList("""
            select l.lesson_id from learning_hub.course_lessons l
            join learning_hub.course_chapters c on c.chapter_id = l.chapter_id
            where c.revision_id = ? order by c.sort_no, l.sort_no
            """, Long.class, revisionId);
    }

    private Long sourceOf(long lessonId) {
        return jdbc.queryForObject("select source_lesson_id from learning_hub.course_lessons where lesson_id = ?",
            Long.class, lessonId);
    }

    private long insertLesson(long revisionId) {
        long chapter = jdbc.queryForObject("select chapter_id from learning_hub.course_chapters where revision_id = ?"
            + " order by sort_no limit 1", Long.class, revisionId);
        return jdbc.queryForObject("""
            insert into learning_hub.course_lessons (chapter_id, title, lesson_type, estimated_duration_seconds,
                is_required, sort_no, created_by, updated_by)
            values (?, 'Bài thêm', 'content', 60, false, 99, 'PT', 'PT') returning lesson_id
            """, Long.class, chapter);
    }

    private long enrollment(String user, long courseId, long revisionId, int cycle, String status) {
        return jdbc.queryForObject("""
            insert into learning_hub.course_enrollments (course_id, revision_id, user_id, cycle_no, enrollment_origin,
                enrolled_at, status, progress_percent, created_by, updated_by)
            values (?, ?, ?, ?, 'assigned', now(), ?, 0, ?, ?) returning enrollment_id
            """, Long.class, courseId, revisionId, user, cycle, status, user, user);
    }

    private void lessonDone(long enrollmentId, long lessonId) {
        jdbc.update("""
            insert into learning_hub.lesson_progress (enrollment_id, lesson_id, status, progress_percent, completed_at,
                created_by, updated_by)
            values (?, ?, 'completed', 100, now(), 'PT', 'PT')
            """, enrollmentId, lessonId);
    }

    private void reward(long revisionId, int pointsValue) {
        jdbc.update("update learning_hub.course_completion_rules set reward_points = ? where revision_id = ?",
            pointsValue, revisionId);
    }

    private long quiz(boolean publish) {
        return fixtures.quiz(quizBody(course.id()), questions, publish);
    }

    private void attempt(String user, long quizId, int attemptNo, String status, boolean passed, int scorePercent) {
        jdbc.update("""
            insert into learning_hub.quiz_attempts (quiz_id, user_id, enrollment_id, attempt_no, status, started_at,
                deadline_at, submitted_at, graded_at, time_limit_minutes, pass_percent, scoring_mode, grace_seconds,
                total_questions, max_score, raw_score, score_percent, passed)
            values (?, ?, 0, ?, ?, now() - interval '1 hour', now() + interval '1 hour', now(), now(), 30, 70,
                'ALL_OR_NOTHING', 0, 1, 100, ?, ?, ?)
            """, quizId, user, attemptNo, status, scorePercent, scorePercent, passed);
    }

    /** Một bài học hoàn thành mỗi ngày, count ngày liên tiếp kết thúc hôm nay. */
    private void activity(String user, int count) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        for (int i = 0; i < count; i++) {
            activityOn(user, today.minusDays(i));
        }
    }

    private void activityOn(String user, LocalDate... days) {
        for (LocalDate day : days) {
            long enrollment = enrollment(user, course.id(), course.revisionId(), CYCLES.incrementAndGet(),
                "in_progress");
            Instant at = day.atTime(12, 0).atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant();
            jdbc.update("""
                insert into learning_hub.lesson_progress (enrollment_id, lesson_id, status, progress_percent,
                    completed_at, created_by, updated_by)
                values (?, ?, 'completed', 100, ?, 'PT', 'PT')
                """, enrollment, insertLesson(course.revisionId()), java.sql.Timestamp.from(at));
        }
    }
}
