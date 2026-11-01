package vn.com.coreacademy.learning;

import java.time.LocalDate;
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
import vn.com.coreacademy.learning.config.LearningPointsProperties;
import vn.com.coreacademy.learning.dto.LearningPointsViews.MyPoints;
import vn.com.coreacademy.learning.dto.LearningPointsViews.PointParts;
import vn.com.coreacademy.learning.service.LearningPointsService;
import vn.com.coreacademy.learning.service.LearningQuizDirectory;

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
    private LearningQuizDirectory quizDirectory;

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
    void weightsComeFromConfiguration() {
        assertThat(weights.getLesson()).isEqualTo(5);
        assertThat(weights.getQuizPass()).isEqualTo(30);
        assertThat(weights.getStreakDay()).isEqualTo(10);
        assertThat(weights.getStreakCap()).isEqualTo(30);
    }

    @Test
    void aLessonCompletedInTwoCyclesOnTwoRevisionsCountsOnce() {
        long first = lessonOf(course.revisionId()).get(0);
        long secondRevision = publishNewRevision();
        long second = lessonOf(secondRevision).get(0);
        assertThat(sourceOf(second)).as("a clone points to the original lesson").isEqualTo(first);
        long thirdRevision = publishNewRevision();
        long third = lessonOf(thirdRevision).get(0);
        assertThat(sourceOf(third)).as("not to the previous copy").isEqualTo(first);

        String user = "PT-LESSONS";
        lessonDone(enrollment(user, course.id(), course.revisionId(), 1, "completed"), first);
        lessonDone(enrollment(user, course.id(), secondRevision, 2, "completed"), second);
        lessonDone(enrollment(user, course.id(), thirdRevision, 3, "in_progress"), third);
        assertThat(parts(user).lessons()).isEqualTo(5);

        // Một bài khác hẳn thì tính thêm.
        long other = insertLesson(course.revisionId());
        lessonDone(enrollment(user, course.id(), course.revisionId(), 4, "in_progress"), other);
        assertThat(parts(user).lessons()).isEqualTo(10);
    }

    @Test
    void aLessonThatIsOnlyStartedEarnsNothing() {
        String user = "PT-STARTED";
        long enrollment = enrollment(user, course.id(), course.revisionId(), 1, "in_progress");
        jdbc.update("""
            insert into learning_hub.lesson_progress (enrollment_id, lesson_id, status, progress_percent, created_by,
                updated_by)
            values (?, ?, 'in_progress', 60, ?, ?)
            """, enrollment, course.lessonId(), user, user);

        assertThat(points.scores(List.of(user), TODAY).get(user).total()).isZero();
    }

    @Test
    void aCourseCompletedTwiceCountsItsLatestRewardOnce() {
        long secondRevision = publishNewRevision();
        reward(course.revisionId(), 50);
        reward(secondRevision, 70);
        Course other = fixtures.course();
        reward(other.revisionId(), 20);

        String user = "PT-COURSES";
        enrollment(user, course.id(), course.revisionId(), 1, "completed");
        enrollment(user, course.id(), secondRevision, 2, "completed");
        enrollment(user, other.id(), other.revisionId(), 1, "completed");
        // Lần học đang học dở hoặc đã rút không cho điểm thưởng.
        enrollment(user, course.id(), secondRevision, 3, "in_progress");
        Course dropped = fixtures.course();
        reward(dropped.revisionId(), 1000);
        enrollment(user, dropped.id(), dropped.revisionId(), 1, "withdrawn");

        assertThat(parts(user).courses()).isEqualTo(90);
    }

    @Test
    void aQuizCountsOncePublishedAndPassed() {
        long passedTwice = quiz(true);
        long failedOnly = quiz(true);
        long unpublished = quiz(false);
        long deleted = quiz(true);
        long passedOnceMore = quiz(true);
        jdbc.update("update learning_hub.quizzes set is_deleted = true where quiz_id = ?", deleted);
        String user = "PT-QUIZZES";
        attempt(user, passedTwice, 1, "SUBMITTED", true);
        attempt(user, passedTwice, 2, "AUTO_SUBMITTED", true);
        attempt(user, failedOnly, 1, "SUBMITTED", false);
        attempt(user, unpublished, 1, "SUBMITTED", true);
        attempt(user, deleted, 1, "SUBMITTED", true);
        attempt(user, passedOnceMore, 1, "SUBMITTED", true);
        attempt(user, passedOnceMore, 2, "VOIDED", true);

        assertThat(quizDirectory.passedQuizCounts(List.of(user, "PT-NOBODY"))).containsExactly(Map.entry(user, 2));
        assertThat(quizDirectory.passedQuizCounts(List.of())).isEmpty();
        assertThat(parts(user).quizzes()).isEqualTo(60);
    }

    @Test
    void streakEndsTodayOrYesterdayAndStopsAtAGap() {
        activity("PT-TODAY", TODAY);
        activity("PT-YESTERDAY", TODAY.minusDays(1));
        activity("PT-GAP", TODAY, TODAY.minusDays(1), TODAY.minusDays(3), TODAY.minusDays(4), TODAY.minusDays(5));
        activity("PT-STALE", TODAY.minusDays(2), TODAY.minusDays(3));
        activity("PT-TWICE", TODAY, TODAY, TODAY.minusDays(1));

        Map<String, MyPoints> result = points.scores(List.of("PT-TODAY", "PT-YESTERDAY", "PT-GAP", "PT-STALE",
            "PT-TWICE", "PT-NONE"), TODAY);

        assertThat(result.get("PT-TODAY").streakDays()).isEqualTo(1);
        assertThat(result.get("PT-YESTERDAY").streakDays()).as("not dropped until a day is missed").isEqualTo(1);
        assertThat(result.get("PT-GAP").streakDays()).isEqualTo(2);
        assertThat(result.get("PT-GAP").parts().streak()).isEqualTo(20);
        assertThat(result.get("PT-STALE").streakDays()).isZero();
        assertThat(result.get("PT-TWICE").streakDays()).as("two rows of one day are one day").isEqualTo(2);
        assertThat(result.get("PT-NONE").total()).isZero();
    }

    @Test
    void theStreakPointsStopAtTheCapButTheStreakKeepsCounting() {
        LocalDate[] days = new LocalDate[40];
        for (int i = 0; i < days.length; i++) {
            days[i] = TODAY.minusDays(i);
        }
        activity("PT-LONG", days);

        MyPoints result = points.scores(List.of("PT-LONG"), TODAY).get("PT-LONG");

        assertThat(result.streakDays()).isEqualTo(40);
        assertThat(result.parts().streak()).isEqualTo(300);
        assertThat(result.total()).isEqualTo(300);
    }

    @Test
    void theStreakFollowsTheLocalDayAcrossMidnight() {
        activity("PT-MIDNIGHT", LocalDate.of(2026, 10, 30));

        assertThat(points.scores(List.of("PT-MIDNIGHT"), LocalDate.of(2026, 10, 30)).get("PT-MIDNIGHT")
            .streakDays()).isEqualTo(1);
        assertThat(points.scores(List.of("PT-MIDNIGHT"), LocalDate.of(2026, 10, 31)).get("PT-MIDNIGHT")
            .streakDays()).as("00:30 local on the 31st: yesterday still counts").isEqualTo(1);
        assertThat(points.scores(List.of("PT-MIDNIGHT"), LocalDate.of(2026, 11, 1)).get("PT-MIDNIGHT")
            .streakDays()).as("a whole day missed").isZero();
    }

    @Test
    void totalIsTheSumOfAllFourParts() {
        String user = "PT-ALL";
        long first = lessonOf(course.revisionId()).get(0);
        lessonDone(enrollment(user, course.id(), course.revisionId(), 1, "completed"), first);
        reward(course.revisionId(), 40);
        attempt(user, quiz(true), 1, "SUBMITTED", true);
        activity(user, TODAY, TODAY.minusDays(1), TODAY.minusDays(2));

        MyPoints result = points.scores(List.of(user), TODAY).get(user);

        assertThat(result.parts().lessons()).isEqualTo(5);
        assertThat(result.parts().quizzes()).isEqualTo(30);
        assertThat(result.parts().courses()).isEqualTo(40);
        assertThat(result.parts().streak()).isEqualTo(30);
        assertThat(result.total()).isEqualTo(105);
    }

    @Test
    void theEndpointReturnsTheCallersPointsAndRefusesANonLearner() {
        JsonNode mine = api.ok(LEARNER, get("/api/v1/me/learning/points"));

        assertThat(mine.fieldNames()).toIterable().containsExactly("total", "parts", "streakDays");
        assertThat(mine.path("parts").fieldNames()).toIterable()
            .containsExactly("lessons", "quizzes", "courses", "streak");
        assertThat(mine.path("total").asLong()).isEqualTo(mine.path("parts").path("lessons").asLong()
            + mine.path("parts").path("quizzes").asLong() + mine.path("parts").path("courses").asLong()
            + mine.path("parts").path("streak").asLong());
        api.http(ADMIN, get("/api/v1/me/learning/points"), 403, "CORE-ACCESS_DENIED");
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

    private void attempt(String user, long quizId, int attemptNo, String status, boolean passed) {
        jdbc.update("""
            insert into learning_hub.quiz_attempts (quiz_id, user_id, enrollment_id, attempt_no, status, started_at,
                deadline_at, submitted_at, graded_at, time_limit_minutes, pass_percent, scoring_mode, grace_seconds,
                total_questions, max_score, passed)
            values (?, ?, 0, ?, ?, now() - interval '1 hour', now() + interval '1 hour', now(), now(), 30, 70,
                'ALL_OR_NOTHING', 0, 1, 1, ?)
            """, quizId, user, attemptNo, status, passed);
    }

    private void activity(String user, LocalDate... days) {
        for (LocalDate day : days) {
            jdbc.update("insert into learning_hub.learning_activity_days (user_id, activity_date, first_source)"
                + " values (?, ?, 'session')", user, day);
        }
    }
}
