package vn.com.coreacademy.learning;

import java.time.Instant;
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
import vn.com.coreacademy.learning.constants.LearningCodes.ReminderKind;
import vn.com.coreacademy.learning.repository.BadgeRepository;
import vn.com.coreacademy.learning.service.BadgeService;
import vn.com.coreacademy.learning.service.LevelService;
import vn.com.coreacademy.learning.service.XpLedgerService;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.delete;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/**
 * Thông báo đạt huy hiệu và lên cấp trong dòng nhắc học: chỉ tạo khi đạt do sự kiện, trao bù thì im lặng, mỗi lần một
 * thông báo, đánh dấu đã hiện toast không làm thông báo biến mất, đọc thì biến mất, và chỉ chủ của nó chạm được.
 */
class GamificationNoticeIT extends AssessmentIntegrationTest {
    private static final AtomicInteger SEQUENCE = new AtomicInteger(800);
    private static final String REMINDERS = "/api/v1/me/learning/reminders";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BadgeService badges;

    @Autowired
    private BadgeRepository badgeRepository;

    @Autowired
    private LevelService levels;

    @Autowired
    private XpLedgerService xp;

    private ApiClient api;
    private Course course;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        course = fixtures.course(LEARNER);
        xp.currentConfig();
    }

    @BeforeEach
    void clean() {
        jdbc.update("delete from learning_hub.user_badges");
        jdbc.update("delete from learning_hub.badge_definitions");
        jdbc.update("delete from learning_hub.learning_reminders where kind <> 'DEADLINE'");
    }

    private String user() {
        return "NT-" + SEQUENCE.incrementAndGet();
    }

    private long firstCourseBadge() {
        return badgeRepository.insertDefinition("FIRST_COURSE", "Khóa học đầu tiên", "Mô tả", "award",
            BadgeRule.COURSES_COMPLETED, 1, null, 0, null).orElseThrow();
    }

    private void completeCourse(String user) {
        jdbc.queryForObject("""
            insert into learning_hub.course_enrollments (course_id, revision_id, user_id, cycle_no, enrollment_origin,
                enrolled_at, status, progress_percent, completed_at, created_by, updated_by)
            values (?, ?, ?, ?, 'assigned', now() - interval '30 days', 'completed', 100, now(), ?, ?)
            returning enrollment_id
            """, Long.class, course.id(), course.revisionId(), user, SEQUENCE.incrementAndGet(), user, user);
        xp.courseCompleted(user, course.id(), 0, Instant.now());
    }

    private List<Map<String, Object>> noticesOf(String user) {
        return jdbc.queryForList("select kind, subject, status, sent_at from learning_hub.learning_reminders"
            + " where user_id = ? order by reminder_id", user);
    }

    private void notice(String user, String kind, String subject, String key) {
        jdbc.update("""
            insert into learning_hub.learning_reminders (user_id, kind, subject, scheduled_at, due_at_snapshot, channel,
                status, attempt_count, idempotency_key, created_by, updated_by)
            values (?, ?, ?, now() - interval '1 minute', now() - interval '1 minute', 'in_app', 'pending', 0, ?,
                'SYSTEM', 'SYSTEM')
            """, user, kind, subject, key);
    }

    private void xp(String user, int amount) {
        jdbc.update("""
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                config_version_id)
            select ?, ?, now(), 'LESSON', ?, version_id
            from learning_hub.gamification_config_versions where version_no = 1
            """, user, amount, "nt-" + SEQUENCE.incrementAndGet());
    }

    @Test
    void anEarnedBadgeCreatesOneBadgeNoticeWithItsName() {
        firstCourseBadge();
        String user = user();

        completeCourse(user);
        completeCourse(user);

        assertThat(noticesOf(user)).hasSize(1);
        assertThat(noticesOf(user).get(0)).containsEntry("kind", ReminderKind.BADGE_EARNED)
            .containsEntry("subject", "Khóa học đầu tiên").containsEntry("status", "pending");
        assertThat(noticesOf(user).get(0).get("sent_at")).as("the toast has not been shown").isNull();
    }

    @Test
    void aBackfilledBadgeCreatesNoNotice() {
        String user = user();
        completeCourse(user);
        long badge = firstCourseBadge();

        badges.backfill(badge);

        assertThat(jdbc.queryForObject("select count(*) from learning_hub.user_badges where user_id = ?",
            Long.class, user)).as("the badge is awarded").isEqualTo(1L);
        assertThat(noticesOf(user)).as("but silently").isEmpty();
    }

    @Test
    void reachingANewLevelCreatesOneLevelUpNoticeAndNothingForTheFirstLevelOrAStandstill() {
        String user = user();
        xp(user, 10);
        levels.refresh(user);
        assertThat(noticesOf(user)).as("starting at level 1 is not a level up").isEmpty();

        xp(user, 300);
        levels.refresh(user);
        levels.refresh(user);

        assertThat(noticesOf(user)).hasSize(1);
        assertThat(noticesOf(user).get(0)).containsEntry("kind", ReminderKind.LEVEL_UP)
            .containsKey("subject");
    }

    @Test
    void theFeedListsNoticesWithTheirKindAndSubjectAndSeenKeepsThemUntilRead() {
        notice(LEARNER, ReminderKind.LEVEL_UP, "Giao dịch viên", "level:" + LEARNER + ":2");
        notice(LEARNER, ReminderKind.BADGE_EARNED, "Khóa học đầu tiên", "badge:" + LEARNER + ":1");

        JsonNode feed = api.ok(LEARNER, get(REMINDERS));
        JsonNode notice = null;
        for (JsonNode item : feed) {
            if (ReminderKind.BADGE_EARNED.equals(item.path("kind").asText())) {
                notice = item;
            }
        }
        assertThat(notice).isNotNull();
        assertThat(notice.path("subject").asText()).isEqualTo("Khóa học đầu tiên");
        assertThat(notice.path("sentAt").isNull()).isTrue();
        long id = notice.path("id").asLong();

        JsonNode seen = api.ok(LEARNER, api.post(Map.of(), REMINDERS + "/" + id + "/seen"));
        assertThat(seen.path("sentAt").isNull()).isFalse();
        assertThat(seen.path("status").asText()).isEqualTo("pending");
        Object stored = jdbc.queryForObject("select sent_at from learning_hub.learning_reminders where reminder_id = ?",
            Object.class, id);
        api.ok(LEARNER, api.post(Map.of(), REMINDERS + "/" + id + "/seen"));
        assertThat(jdbc.queryForObject("select sent_at from learning_hub.learning_reminders where reminder_id = ?",
            Object.class, id)).as("seen twice changes nothing").isEqualTo(stored);
        assertThat(api.ok(LEARNER, get(REMINDERS)).findValuesAsText("id")).contains(String.valueOf(id));

        api.ok(LEARNER, api.post(Map.of(), REMINDERS + "/" + id + "/read"));
        assertThat(api.ok(LEARNER, get(REMINDERS)).findValuesAsText("id")).doesNotContain(String.valueOf(id));
        JsonNode inbox = api.ok(LEARNER, get(REMINDERS + "/inbox"));
        assertThat(inbox.findValuesAsText("id")).contains(String.valueOf(id));
        assertThat(inbox.findValuesAsText("status")).contains("pending", "sent");

        JsonNode deleted = api.ok(LEARNER, delete(REMINDERS + "/" + id));
        assertThat(deleted.path("status").asText()).isEqualTo("cancelled");
        api.ok(LEARNER, api.post(Map.of(), REMINDERS + "/" + id + "/read"));
        assertThat(api.ok(LEARNER, get(REMINDERS + "/inbox")).findValuesAsText("id"))
            .doesNotContain(String.valueOf(id));
    }

    @Test
    void deadlineContextBelongsToTheRecipientAndSurvivesReadAndRevocation() {
        long recipient = jdbc.queryForObject("select recipient_id from learning_hub.assignment_recipients "
            + "where enrollment_id in (select enrollment_id from learning_hub.course_enrollments where course_id = ? and user_id = ?) limit 1",
            Long.class, course.id(), LEARNER);
        long reminder = jdbc.queryForObject("""
            insert into learning_hub.learning_reminders (user_id, recipient_id, kind, scheduled_at, due_at_snapshot,
                channel, status, attempt_count, idempotency_key, created_by, updated_by)
            values (?, ?, 'DEADLINE', now(), now() + interval '1 day', 'in_app', 'pending', 0, ?, 'SYSTEM', 'SYSTEM')
            returning reminder_id
            """, Long.class, LEARNER, recipient, "ux-deadline:" + SEQUENCE.incrementAndGet());
        JsonNode seen = api.ok(LEARNER, api.post(Map.of(), REMINDERS + "/" + reminder + "/seen"));
        assertThat(seen.path("courseId").asLong()).isEqualTo(course.id());
        assertThat(seen.path("courseTitle").asText()).isEqualTo("Khóa kiểm thử Quiz");
        assertThat(seen.path("enrollmentId").asLong()).isPositive();
        assertThat(seen.path("courseAvailable").asBoolean()).isTrue();
        JsonNode read = api.ok(LEARNER, api.post(Map.of(), REMINDERS + "/" + reminder + "/read"));
        assertThat(read.path("courseId").asLong()).isEqualTo(course.id());
        assertThat(api.ok(LEARNER, get(REMINDERS + "/inbox")).findValuesAsText("courseTitle"))
            .contains("Khóa kiểm thử Quiz");
        api.http(LEARNER_2, api.post(Map.of(), REMINDERS + "/" + reminder + "/read"), 403, "CORE-ACCESS_DENIED");
        jdbc.update("update learning_hub.assignment_recipients set status = 'revoked' where recipient_id = ?", recipient);
        try {
            JsonNode revoked = api.ok(LEARNER, api.post(Map.of(), REMINDERS + "/" + reminder + "/read"));
            assertThat(revoked.path("courseAvailable").asBoolean()).isFalse();
            assertThat(revoked.path("courseTitle").asText()).isEqualTo("Khóa kiểm thử Quiz");
        } finally {
            jdbc.update("update learning_hub.assignment_recipients set status = 'active' where recipient_id = ?", recipient);
        }
    }

    @Test
    void onlyTheOwnerOfANoticeCanMarkItSeenOrRead() {
        notice(LEARNER, ReminderKind.LEVEL_UP, "Giao dịch viên", "level:" + LEARNER + ":9");
        long id = jdbc.queryForObject("select reminder_id from learning_hub.learning_reminders where user_id = ?"
            + " and idempotency_key = ?", Long.class, LEARNER, "level:" + LEARNER + ":9");

        api.http(LEARNER_2, api.post(Map.of(), REMINDERS + "/" + id + "/seen"), 403, "CORE-ACCESS_DENIED");
        api.http(LEARNER_2, api.post(Map.of(), REMINDERS + "/" + id + "/read"), 403, "CORE-ACCESS_DENIED");
        api.http(LEARNER_2, delete(REMINDERS + "/" + id), 403, "CORE-ACCESS_DENIED");
        assertThat(api.ok(LEARNER_2, get(REMINDERS)).findValuesAsText("id")).doesNotContain(String.valueOf(id));
        assertThat(api.ok(LEARNER_2, get(REMINDERS + "/inbox")).findValuesAsText("id"))
            .doesNotContain(String.valueOf(id));
    }
}
