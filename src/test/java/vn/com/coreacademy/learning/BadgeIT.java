package vn.com.coreacademy.learning;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.learning.constants.LearningCodes.BadgeRule;
import vn.com.coreacademy.learning.repository.BadgeRepository;
import vn.com.coreacademy.learning.service.BadgeService;
import vn.com.coreacademy.learning.service.XpLedgerService;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/**
 * Huy hiệu: từng loại luật được kiểm tra khi sự kiện của nó xảy ra, trao bù âm thầm khi tạo huy hiệu, huy hiệu ngừng
 * dùng. Người trong test là mã giả (bảng không có FK) để mỗi ca có dữ liệu riêng; mỗi ca bắt đầu từ bảng huy hiệu trống.
 */
class BadgeIT extends AssessmentIntegrationTest {
    private static final AtomicInteger SEQUENCE = new AtomicInteger(500);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BadgeService badges;

    @Autowired
    private BadgeRepository repository;

    @Autowired
    private XpLedgerService xp;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private Course first;
    private Course second;
    private List<Long> questions;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        first = fixtures.course(LEARNER);
        second = fixtures.course(LEARNER);
        long competency = fixtures.competency();
        questions = List.of(fixtures.question(first.id(), competency, "SINGLE", 0),
            fixtures.question(first.id(), competency, "SINGLE", 1));
        xp.currentConfig();
    }

    @BeforeEach
    void emptyBadges() {
        jdbc.update("delete from learning_hub.user_badges");
        jdbc.update("delete from learning_hub.badge_definitions");
    }

    private String user() {
        return "BG-" + SEQUENCE.incrementAndGet();
    }

    private long badge(String code, String rule, Integer n, Long program) {
        return repository.insertDefinition(code, "Huy hiệu " + code, "Mô tả " + code, "award", rule, n, program, 0,
            null).orElseThrow();
    }

    private long enrollment(String user, Course course, String status, Instant completedAt) {
        return jdbc.queryForObject("""
            insert into learning_hub.course_enrollments (course_id, revision_id, user_id, cycle_no, enrollment_origin,
                enrolled_at, status, progress_percent, completed_at, created_by, updated_by)
            values (?, ?, ?, ?, 'assigned', now() - interval '30 days', ?, 0, ?, ?, ?) returning enrollment_id
            """, Long.class, course.id(), course.revisionId(), user, SEQUENCE.incrementAndGet(), status,
            completedAt == null ? null : java.sql.Timestamp.from(completedAt), user, user);
    }

    private List<Map<String, Object>> held(String user) {
        return jdbc.queryForList("select b.code, u.source from learning_hub.user_badges u"
            + " join learning_hub.badge_definitions b on b.badge_id = u.badge_id where u.user_id = ? order by b.code",
            user);
    }

    private List<String> codes(String user) {
        return held(user).stream().map(row -> (String) row.get("code")).toList();
    }

    @Test
    void nCoursesCompletedIsAwardedOnTheCourseThatReachesNOnce() {
        badge("TWO_COURSES", BadgeRule.COURSES_COMPLETED, 2, null);
        String user = user();

        enrollment(user, first, "completed", Instant.now());
        xp.courseCompleted(user, first.id(), 0, Instant.now());
        assertThat(codes(user)).as("one course is not two").isEmpty();

        enrollment(user, second, "completed", Instant.now());
        xp.courseCompleted(user, second.id(), 0, Instant.now());
        assertThat(held(user)).extracting(row -> row.get("code"), row -> row.get("source"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("TWO_COURSES", "EVENT"));

        xp.courseCompleted(user, second.id(), 0, Instant.now());
        assertThat(held(user)).as("a repeated event awards nothing twice").hasSize(1);
    }

    @Test
    void relearningTheSameCourseDoesNotCountTwice() {
        badge("TWO_COURSES", BadgeRule.COURSES_COMPLETED, 2, null);
        String user = user();

        enrollment(user, first, "completed", Instant.now());
        enrollment(user, first, "completed", Instant.now());
        xp.courseCompleted(user, first.id(), 0, Instant.now());

        assertThat(codes(user)).isEmpty();
    }

    @Test
    void aProgrammeBadgeNeedsEveryCourseOfTheProgramme() {
        Course first = fixtures.course(LEARNER);
        Course second = fixtures.course(LEARNER);
        jdbc.update("""
            update learning_hub.course_revisions set program_id =
                (select program_id from learning_hub.course_revisions where revision_id = ?) where revision_id = ?
            """, first.revisionId(), second.revisionId());
        long program = jdbc.queryForObject("select program_id from learning_hub.course_revisions where revision_id = ?",
            Long.class, first.revisionId());
        badge("PROGRAM", BadgeRule.PROGRAM_COMPLETED, null, program);
        String user = user();

        enrollment(user, first, "completed", Instant.now());
        xp.courseCompleted(user, first.id(), 0, Instant.now());
        assertThat(codes(user)).isEmpty();
        assertThat(repository.programProgress(program, user)).as("done / total").containsExactly(1, 2);

        enrollment(user, second, "completed", Instant.now());
        xp.courseCompleted(user, second.id(), 0, Instant.now());
        assertThat(codes(user)).containsExactly("PROGRAM");
        assertThat(repository.programProgress(program, user)).containsExactly(2, 2);
    }

    @Test
    void aFirstTryPerfectNeedsThe100PercentOnAttemptOneOnDistinctQuizzes() {
        badge("PERFECT_2", BadgeRule.FIRST_TRY_PERFECT, 2, null);
        String user = user();
        long quizA = fixtures.quiz(quizBody(first.id()), questions, true);
        long quizB = fixtures.quiz(quizBody(first.id()), questions, true);

        attempt(user, quizA, 1, 100);
        xp.quizPassed(user, quizA, 1, BigDecimal.valueOf(100), Instant.now());
        assertThat(codes(user)).isEmpty();

        attempt(user, quizB, 2, 100);
        xp.quizPassed(user, quizB, 2, BigDecimal.valueOf(100), Instant.now());
        assertThat(codes(user)).as("a perfect score on the second attempt does not count").isEmpty();

        attempt(user, quizB, 1, 95);
        xp.quizPassed(user, quizB, 1, BigDecimal.valueOf(95), Instant.now());
        assertThat(codes(user)).as("95% is not 100%").isEmpty();

        long quizC = fixtures.quiz(quizBody(first.id()), questions, true);
        attempt(user, quizC, 1, 100);
        xp.quizPassed(user, quizC, 1, BigDecimal.valueOf(100), Instant.now());
        assertThat(codes(user)).containsExactly("PERFECT_2");
    }

    @Test
    void anAssignedCourseFinishedByItsDeadlineCountsAndALateOneDoesNot() {
        badge("ON_TIME_2", BadgeRule.ASSIGNED_BEFORE_DEADLINE, 2, null);
        String user = user();
        Instant done = Instant.now().minusSeconds(3600);
        long e1 = enrollment(user, first, "completed", done);
        long e2 = enrollment(user, second, "completed", done);
        assignment(user, first, e1, done.plusSeconds(86_400));
        long late = assignment(user, second, e2, done.minusSeconds(86_400));

        xp.courseCompleted(user, second.id(), 0, Instant.now());
        assertThat(codes(user)).as("one on time and one late").isEmpty();

        jdbc.update("update learning_hub.assignment_recipients set effective_due_at = ? where assignment_id = ?",
            java.sql.Timestamp.from(done.plusSeconds(60)), late);
        xp.courseCompleted(user, second.id(), 0, Instant.now());
        assertThat(codes(user)).containsExactly("ON_TIME_2");
    }

    @Test
    void aRevokedAssignmentDoesNotCountBeforeTheDeadline() {
        badge("ON_TIME_1", BadgeRule.ASSIGNED_BEFORE_DEADLINE, 1, null);
        String user = user();
        Instant done = Instant.now().minusSeconds(3600);
        long e1 = enrollment(user, first, "completed", done);
        long assignment = assignment(user, first, e1, done.plusSeconds(86_400));
        jdbc.update("update learning_hub.assignment_recipients set status = 'revoked' where assignment_id = ?",
            assignment);

        xp.courseCompleted(user, first.id(), 0, Instant.now());

        assertThat(codes(user)).isEmpty();
    }

    @Test
    void aStreakOfNWorkingDaysIsAwardedOnTheLessonThatReachesIt() {
        badge("STREAK_3", BadgeRule.STREAK_WORKING_DAYS, 3, null);
        String user = user();
        LocalDate monday = LocalDate.now().minusWeeks(3).with(java.time.DayOfWeek.MONDAY);

        lesson(user, monday);
        lesson(user, monday.plusDays(1));
        xp.lessonCompleted(user, 1L, Instant.now());
        assertThat(codes(user)).as("two working days").isEmpty();

        lesson(user, monday.plusDays(2));
        xp.lessonCompleted(user, 2L, Instant.now());
        assertThat(codes(user)).containsExactly("STREAK_3");
    }

    @Test
    void creatingABadgeAwardsEveryoneWhoAlreadyQualifiesSilentlyAndARerunAwardsNothingMore() {
        String early = user();
        String none = user();
        enrollment(early, first, "completed", Instant.now());
        enrollment(none, first, "in_progress", null);
        long id = badge("NEW_ONE", BadgeRule.COURSES_COMPLETED, 1, null);

        int awarded = badges.backfill(id);

        assertThat(awarded).isGreaterThanOrEqualTo(1);
        assertThat(held(early)).extracting(row -> row.get("source")).containsExactly("BACKFILL");
        assertThat(codes(none)).isEmpty();
        assertThat(badges.backfill(id)).as("a rerun awards nothing twice").isZero();
        assertThat(held(early)).hasSize(1);
    }

    @Test
    void aRetiredBadgeIsNoLongerAwardedButHoldersKeepIt() {
        long id = badge("RETIRING", BadgeRule.COURSES_COMPLETED, 1, null);
        String holder = user();
        String later = user();
        enrollment(holder, first, "completed", Instant.now());
        xp.courseCompleted(holder, first.id(), 0, Instant.now());
        assertThat(codes(holder)).containsExactly("RETIRING");

        jdbc.update("update learning_hub.badge_definitions set status = 'retired' where badge_id = ?", id);
        enrollment(later, first, "completed", Instant.now());
        xp.courseCompleted(later, first.id(), 0, Instant.now());

        assertThat(codes(later)).as("a retired badge is not awarded").isEmpty();
        assertThat(badges.backfill(id)).as("nor backfilled").isZero();
        assertThat(codes(holder)).as("holders keep it").containsExactly("RETIRING");
    }

    @Test
    void theProfileListsEarnedThenNotEarnedWithConditionsAndProgrammeProgress() {
        badge("PROGRAM_X", BadgeRule.PROGRAM_COMPLETED, null, programOf(first));
        long retired = badge("RETIRED_ONE", BadgeRule.STREAK_WORKING_DAYS, 99, null);
        jdbc.update("update learning_hub.badge_definitions set status = 'retired' where badge_id = ?", retired);
        long mine = badge("EARNED_ONE", BadgeRule.COURSES_COMPLETED, 50, null);
        award(LEARNER, mine);

        JsonNode profile = api.ok(LEARNER, get("/api/v1/me/learning/badges"));

        assertThat(profile.fieldNames()).toIterable().containsExactly("earned", "notEarned");
        assertThat(profile.path("earned")).hasSize(1);
        JsonNode earned = profile.path("earned").get(0);
        assertThat(earned.fieldNames()).toIterable().containsExactly("badgeId", "name", "description", "icon",
            "condition", "earnedAt", "retired", "progress");
        assertThat(earned.path("condition").asText()).isEqualTo("Hoàn thành 50 khóa học");
        assertThat(earned.path("earnedAt").isNull()).isFalse();
        assertThat(earned.path("progress").isNull()).isTrue();
        assertThat(profile.path("notEarned")).as("a retired badge nobody holds is not listed").hasSize(1);
        JsonNode missing = profile.path("notEarned").get(0);
        assertThat(missing.path("earnedAt").isNull()).isTrue();
        assertThat(missing.path("condition").asText()).startsWith("Hoàn thành mọi khóa học của chương trình");
        assertThat(missing.path("progress").path("total").asInt()).isEqualTo(1);
        assertThat(missing.path("progress").path("done").asInt()).isBetween(0, 1);
    }

    @Test
    void aHolderStillSeesARetiredBadgeMarkedRetired() {
        long id = badge("OLD_ONE", BadgeRule.COURSES_COMPLETED, 1, null);
        award(LEARNER, id);
        jdbc.update("update learning_hub.badge_definitions set status = 'retired' where badge_id = ?", id);

        JsonNode profile = api.ok(LEARNER, get("/api/v1/me/learning/badges"));

        assertThat(profile.path("earned")).hasSize(1);
        assertThat(profile.path("earned").get(0).path("retired").asBoolean()).isTrue();
    }

    @Test
    void theFirstReadCreatesTheStarterSetAndOnlyOnce() {
        JsonNode profile = api.ok(LEARNER, get("/api/v1/me/learning/badges"));

        long total = profile.path("earned").size() + profile.path("notEarned").size();
        List<String> rules = jdbc.queryForList("select distinct rule_type from learning_hub.badge_definitions order by 1",
            String.class);
        assertThat(rules).containsExactly("ASSIGNED_BEFORE_DEADLINE", "COURSES_COMPLETED", "FIRST_TRY_PERFECT",
            "PROGRAM_COMPLETED", "STREAK_WORKING_DAYS");
        assertThat(jdbc.queryForList("select code from learning_hub.badge_definitions where code not like 'PROGRAM_%'"
            + " order by code", String.class)).containsExactly("BEFORE_DEADLINE_3", "COURSES_10", "FIRST_COURSE",
            "FIRST_TRY_PERFECT_3", "STREAK_20", "STREAK_5", "STREAK_60");
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.badge_definitions where rule_type"
            + " = 'HOURS_LEARNED'", Long.class)).as("hours learned is not built").isZero();
        assertThat(api.ok(LEARNER, get("/api/v1/me/learning/badges")).path("earned").size()
            + api.ok(LEARNER, get("/api/v1/me/learning/badges")).path("notEarned").size()).isEqualTo(total);
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.badge_definitions", Long.class))
            .isEqualTo(total);
    }

    @Test
    void aNonLearnerIsRefused() {
        api.http(ADMIN, get("/api/v1/me/learning/badges"), 403, "CORE-ACCESS_DENIED");
    }

    private void award(String user, long badgeId) {
        jdbc.update("insert into learning_hub.user_badges (user_id, badge_id, source) values (?, ?, 'EVENT')", user,
            badgeId);
    }

    private long programOf(Course course) {
        return jdbc.queryForObject("select program_id from learning_hub.course_revisions where revision_id = ?",
            Long.class, course.revisionId());
    }

    private void attempt(String user, long quizId, int attemptNo, int scorePercent) {
        jdbc.update("""
            insert into learning_hub.quiz_attempts (quiz_id, user_id, enrollment_id, attempt_no, status, started_at,
                deadline_at, submitted_at, graded_at, time_limit_minutes, pass_percent, scoring_mode, grace_seconds,
                total_questions, max_score, raw_score, score_percent, passed)
            values (?, ?, 0, ?, 'SUBMITTED', now() - interval '1 hour', now() + interval '1 hour', now(), now(), 30, 70,
                'ALL_OR_NOTHING', 0, 1, 100, ?, ?, true)
            """, quizId, user, attemptNo, scorePercent, scorePercent);
    }

    /** Một đợt giao cho người dùng giả với hạn dueAt, gắn vào lần học enrollmentId. Trả assignment_id. */
    private long assignment(String user, Course course, long enrollmentId, Instant dueAt) {
        long id = jdbc.queryForObject("""
            insert into learning_hub.course_assignments (course_id, revision_id, assigning_org_id, assigned_by,
                start_at, due_at, requirement, enrollment_mode, reminder_enabled, reminder_days_before, status,
                idempotency_key, request_hash, created_by, updated_by)
            values (?, ?, ?, 'BG', now() - interval '20 days', ?, 'mandatory', 'auto', false, 0, 'active', ?, 'h',
                'BG', 'BG') returning assignment_id
            """, Long.class, course.id(), course.revisionId(), course.ownerOrgId(), java.sql.Timestamp.from(dueAt),
            "bg-" + SEQUENCE.incrementAndGet());
        jdbc.update("""
            insert into learning_hub.assignment_recipients (assignment_id, user_id, enrollment_id, resolved_at,
                effective_start_at, effective_due_at, requirement, status, created_by, updated_by)
            values (?, ?, ?, now() - interval '20 days', now() - interval '20 days', ?, 'mandatory', 'active', 'BG',
                'BG')
            """, id, user, enrollmentId, java.sql.Timestamp.from(dueAt));
        return id;
    }

    /** Một bài học hoàn thành lúc 10:00 giờ Việt Nam của ngày đó. */
    private void lesson(String user, LocalDate day) {
        long enrollment = jdbc.queryForObject("""
            insert into learning_hub.course_enrollments (course_id, revision_id, user_id, cycle_no, enrollment_origin,
                enrolled_at, status, progress_percent, created_by, updated_by)
            values (?, ?, ?, ?, 'assigned', now(), 'in_progress', 0, ?, ?) returning enrollment_id
            """, Long.class, first.id(), first.revisionId(), user, SEQUENCE.incrementAndGet(), user, user);
        jdbc.update("""
            insert into learning_hub.lesson_progress (enrollment_id, lesson_id, status, progress_percent, completed_at,
                created_by, updated_by)
            values (?, ?, 'completed', 100, (?::timestamp at time zone 'Asia/Ho_Chi_Minh'), 'BG', 'BG')
            """, enrollment, first.lessonId(), day.atTime(10, 0));
    }
}
