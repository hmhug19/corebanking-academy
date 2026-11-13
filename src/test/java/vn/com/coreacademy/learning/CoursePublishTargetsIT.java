package vn.com.coreacademy.learning;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/** Xuất bản kèm đối tượng giao và chia sẻ: tất cả cùng một transaction, lỗi một dòng hủy cả lần xuất bản. */
class CoursePublishTargetsIT extends AssessmentIntegrationTest {
    private static final String PARENT_CODE = "PT-CHILD";
    private static final String GRANDCHILD_CODE = "PT-GRAND";
    private static final String SHARED_CODE = "PT-SHARED";
    private static final String CHILD_LEARNER = "pt-child-1";
    private static final String GRANDCHILD_LEARNER = "pt-grand-1";
    private static final String PUBLISH = "/api/v1/courses/{c}/revisions/{r}/publish";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private long childOrg;
    private long sharedOrg;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        removeOrgs();
        childOrg = org(PARENT_CODE, fixtures.orgId());
        long grandchild = org(GRANDCHILD_CODE, childOrg);
        sharedOrg = org(SHARED_CODE, null);
        learner(CHILD_LEARNER, childOrg);
        learner(GRANDCHILD_LEARNER, grandchild);
    }

    @AfterAll
    void tearDown() {
        removeOrgs();
    }

    private void removeOrgs() {
        jdbc.update("delete from learning_hub.course_shares where org_id in "
            + "(select org_id from learning_hub.orgs where code in (?, ?, ?))",
            PARENT_CODE, GRANDCHILD_CODE, SHARED_CODE);
        jdbc.update("delete from learning_hub.user_roles where user_id in (?, ?)", CHILD_LEARNER, GRANDCHILD_LEARNER);
        jdbc.update("delete from learning_hub.course_enrollments where user_id in (?, ?)",
            CHILD_LEARNER, GRANDCHILD_LEARNER);
        jdbc.update("delete from learning_hub.users where user_id in (?, ?)", CHILD_LEARNER, GRANDCHILD_LEARNER);
        jdbc.update("delete from learning_hub.orgs where code in (?, ?, ?)",
            GRANDCHILD_CODE, PARENT_CODE, SHARED_CODE);
    }

    private long org(String code, Long parent) {
        return jdbc.queryForObject("""
            insert into learning_hub.orgs (parent_id, org_type, code, name) values (?, 'UNIT', ?, ?)
            returning org_id
            """, Long.class, parent, code, "Đơn vị " + code);
    }

    private void learner(String userId, long orgId) {
        jdbc.update("""
            insert into learning_hub.users (user_id, user_name, full_name, org_id, auth_provider, password_hash)
            values (?, ?, ?, ?, 'LOCAL', 'x')
            """, userId, userId, "Học viên " + userId, orgId);
        jdbc.update("""
            insert into learning_hub.user_roles (user_id, role_id, scope)
            select ?, role_id, 'OWN' from learning_hub.roles where code = 'LEARNER'
            """, userId);
    }

    /** Khóa đã có bản 1 xuất bản; trả bản nháp 2 sẵn sàng xuất bản. */
    private record Draft(Course course, long revisionId, long version) {
    }

    private Draft draft() {
        Course course = fixtures.course();
        JsonNode detail = api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/courses/{c}/draft", course.id()));
        JsonNode revision = detail.path("revision");
        return new Draft(course, revision.path("id").asLong(), revision.path("rowVersion").asLong());
    }

    private Map<String, Object> userRow(String userId) {
        return row(Map.of("targetType", "user", "userId", userId, "includeDescendants", false));
    }

    private Map<String, Object> orgRow(long orgId) {
        return row(Map.of("targetType", "org", "orgId", orgId, "includeDescendants", true));
    }

    private Map<String, Object> row(Map<String, Object> target) {
        Map<String, Object> row = new HashMap<>();
        row.put("startAt", "2020-01-01T00:00:00Z");
        row.put("dueAt", "2099-12-31T16:59:59Z");
        row.put("reminderEnabled", true);
        row.put("reminderDaysBefore", 3);
        row.put("target", target);
        return row;
    }

    private Map<String, Object> body(Draft draft, List<Map<String, Object>> rows, List<Long> shares) {
        Map<String, Object> body = new HashMap<>();
        body.put("expectedVersion", draft.version());
        body.put("assignments", rows);
        body.put("shareOrgIds", shares);
        return body;
    }

    private JsonNode publish(Draft draft, Map<String, Object> body) {
        return api.ok(INSTRUCTOR, api.post(body, PUBLISH, draft.course().id(), draft.revisionId()));
    }

    private JsonNode refused(Draft draft, Map<String, Object> body) {
        return api.error(INSTRUCTOR, api.post(body, PUBLISH, draft.course().id(), draft.revisionId()),
            "CORE-VALIDATION_FAILED");
    }

    private String state(Draft draft) {
        return jdbc.queryForObject("select state from learning_hub.course_revisions where revision_id = ?",
            String.class, draft.revisionId());
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private long assignments(Draft draft) {
        return count("select count(*) from learning_hub.course_assignments where course_id = ?",
            draft.course().id());
    }

    private long shares(Draft draft) {
        return count("select count(*) from learning_hub.course_shares where course_id = ?", draft.course().id());
    }

    private long enrolled(Draft draft, String userId) {
        return count("select count(*) from learning_hub.course_enrollments where course_id = ? and user_id = ?",
            draft.course().id(), userId);
    }

    @Test
    void aPersonAnOrgWithSubUnitsAndAShareAreAllWrittenWithThePublish() {
        Draft draft = draft();

        JsonNode published = publish(draft, body(draft, List.of(userRow(LEARNER), orgRow(childOrg)),
            List.of(sharedOrg)));

        assertThat(published.path("revision").path("state").asText()).isEqualTo("published");
        assertThat(state(draft)).isEqualTo("published");
        assertThat(assignments(draft)).isEqualTo(2);
        assertThat(enrolled(draft, LEARNER)).isEqualTo(1);
        assertThat(enrolled(draft, CHILD_LEARNER)).isEqualTo(1);
        assertThat(enrolled(draft, GRANDCHILD_LEARNER)).isEqualTo(1);
        assertThat(enrolled(draft, LEARNER_2)).isZero();
        assertThat(jdbc.queryForList("""
            select title, requirement, enrollment_mode, idempotency_key, reminder_enabled, reminder_days_before,
                   assigning_org_id, revision_id
            from learning_hub.course_assignments where course_id = ? order by assignment_id
            """, draft.course().id())).satisfies(list -> {
                assertThat(list.get(0)).containsEntry("title", null).containsEntry("requirement", "optional")
                    .containsEntry("enrollment_mode", "continue")
                    .containsEntry("idempotency_key", "publish-" + draft.revisionId() + "-0")
                    .containsEntry("reminder_enabled", true).containsEntry("reminder_days_before", 3);
                assertThat(list.get(1)).containsEntry("idempotency_key", "publish-" + draft.revisionId() + "-1");
            });
        assertThat(jdbc.queryForMap("""
            select include_descendants, can_assign, can_self_enroll, status
            from learning_hub.course_shares where course_id = ? and org_id = ?
            """, draft.course().id(), sharedOrg))
            .containsEntry("include_descendants", true).containsEntry("can_assign", true)
            .containsEntry("can_self_enroll", false).containsEntry("status", "active");
    }

    private JsonNode nextRevision(Course course) {
        JsonNode draft = api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/courses/{c}/draft", course.id()));
        return api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", draft.path("revision").path("rowVersion").asLong()),
            PUBLISH, course.id(), draft.path("revision").path("id").asLong()));
    }

    private long recipient(Course course, String user) {
        return jdbc.queryForObject("""
            select ar.recipient_id from learning_hub.assignment_recipients ar
            join learning_hub.course_assignments a on a.assignment_id = ar.assignment_id
            where a.course_id = ? and ar.user_id = ? order by ar.recipient_id limit 1
            """, Long.class, course.id(), user);
    }

    private JsonNode revisionPreview(long recipient) {
        return api.ok(INSTRUCTOR, ApiClient.get("/api/v1/learning-assignments/recipients/{id}/revision-change", recipient));
    }

    private Map<String, Object> changeBody(JsonNode preview, String mode) {
        return Map.of("impactToken", preview.path("impactToken").asText(),
            "enrollmentId", preview.path("enrollmentId").asLong(), "expectedVersion", preview.path("expectedVersion").asLong(),
            "targetRevisionId", preview.path("targetRevisionId").asLong(), "mode", mode,
            "reason", "Cập nhật nội dung bắt buộc", "idempotencyKey", AssessmentFixtures.unique("revision-change"));
    }

    @Test
    void publishingKeepsOldLearningAndExplicitChangesPreserveHistoryAndObligations() {
        Course course = fixtures.course(LEARNER, LEARNER_2);
        long started = recipient(course, LEARNER), untouched = recipient(course, LEARNER_2);
        long oldStarted = jdbc.queryForObject("select enrollment_id from learning_hub.assignment_recipients where recipient_id = ?", Long.class, started);
        jdbc.update("update learning_hub.course_enrollments set status = 'in_progress', started_at = now(), progress_percent = 25 where enrollment_id = ?", oldStarted);
        long next = nextRevision(course).path("revision").path("id").asLong();
        assertThat(jdbc.queryForObject("select revision_id from learning_hub.course_enrollments where enrollment_id = ?", Long.class, oldStarted))
            .isEqualTo(course.revisionId());
        assertThat(jdbc.queryForObject("select progress_percent from learning_hub.course_enrollments where enrollment_id = ?", BigDecimal.class, oldStarted))
            .isEqualByComparingTo("25");
        fixtures.assign(course.id(), next, LEARNER);
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.course_enrollments where course_id = ? and user_id = ?", Long.class, course.id(), LEARNER))
            .isEqualTo(1);
        JsonNode startedPreview = revisionPreview(started);
        assertThat(startedPreview.path("canTransfer").asBoolean()).isFalse();
        api.error(INSTRUCTOR, api.post(changeBody(startedPreview, "transfer_unstarted"),
            "/api/v1/learning-assignments/recipients/{id}/revision-change", started), "LEARNING-CONFLICT");
        JsonNode stale = revisionPreview(untouched);
        fixtures.assign(course.id(), next, LEARNER_2);
        api.error(INSTRUCTOR, api.post(changeBody(stale, "transfer_unstarted"),
            "/api/v1/learning-assignments/recipients/{id}/revision-change", untouched), "LEARNING-CONFLICT");
        JsonNode preview = revisionPreview(untouched);
        assertThat(preview.path("canTransfer").asBoolean()).isTrue();
        assertThat(preview.path("activeObligations").asInt()).isEqualTo(2);
        JsonNode transferred = api.ok(INSTRUCTOR, api.post(changeBody(preview, "transfer_unstarted"),
            "/api/v1/learning-assignments/recipients/{id}/revision-change", untouched));
        assertThat(transferred.path("revisionId").asLong()).isEqualTo(next);
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.assignment_recipients where enrollment_id = ? and status = 'active'", Long.class,
            transferred.path("enrollmentId").asLong())).isEqualTo(2);
        JsonNode relearned = api.ok(INSTRUCTOR, api.post(changeBody(revisionPreview(started), "relearn"),
            "/api/v1/learning-assignments/recipients/{id}/revision-change", started));
        assertThat(relearned.path("enrollmentId").asLong()).isNotEqualTo(oldStarted);
        assertThat(jdbc.queryForObject("select status from learning_hub.course_enrollments where enrollment_id = ?", String.class, oldStarted))
            .isEqualTo("withdrawn");
        assertThat(jdbc.queryForObject("select progress_percent from learning_hub.course_enrollments where enrollment_id = ?", BigDecimal.class,
            relearned.path("enrollmentId").asLong())).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select progress_percent from learning_hub.course_enrollments where enrollment_id = ?", BigDecimal.class,
            oldStarted)).isEqualByComparingTo("25");
    }

    @Test
    void relearningKeepsCompletedCyclesAndTheirCertificates() {
        Course course = fixtures.course(LEARNER);
        long recipient = recipient(course, LEARNER);
        long old = revisionPreview(recipient).path("enrollmentId").asLong();
        jdbc.update("update learning_hub.course_enrollments set status = 'completed', completed_at = now(), progress_percent = 100 where enrollment_id = ?", old);
        nextRevision(course);
        api.ok(INSTRUCTOR, api.post(changeBody(revisionPreview(recipient), "relearn"),
            "/api/v1/learning-assignments/recipients/{id}/revision-change", recipient));
        assertThat(jdbc.queryForObject("select status from learning_hub.course_enrollments where enrollment_id = ?", String.class, old))
            .isEqualTo("completed");
        api.ok(LEARNER, ApiClient.get("/api/v1/me/learning/certificates/{id}", old));
    }

    @Test
    void publishWithoutExtrasIsUnchanged() {
        Draft draft = draft();

        publish(draft, Map.of("expectedVersion", draft.version()));

        assertThat(state(draft)).isEqualTo("published");
        assertThat(assignments(draft)).isZero();
        assertThat(shares(draft)).isZero();
    }

    @Test
    void anEmptyTargetRowRollsBackThePublishTheOtherRowsAndTheShare() {
        Draft draft = draft();
        // Đơn vị riêng không có học viên nào: dòng 1 ném EMPTY_TARGET sau khi dòng 0 và chia sẻ đã ghi.
        long emptyOrg = org("PT-EMPTY", fixtures.orgId());
        try {
            JsonNode body = refused(draft, body(draft, List.of(userRow(LEARNER), orgRow(emptyOrg)),
                List.of(sharedOrg)));

            JsonNode error = body.path("errors").get(0);
            assertThat(body.path("errors")).hasSize(1);
            assertThat(error.path("field").asText()).isEqualTo("assignments[1]");
            assertThat(error.path("code").asText()).isEqualTo("LEARNING-EMPTY_TARGET");
            assertThat(error.path("message").asText()).isNotBlank();
            assertThat(state(draft)).isEqualTo("draft");
            assertThat(assignments(draft)).isZero();
            assertThat(shares(draft)).isZero();
            assertThat(enrolled(draft, LEARNER)).isZero();
        } finally {
            jdbc.update("delete from learning_hub.orgs where org_id = ?", emptyOrg);
        }
    }

    @Test
    void aShareToTheOwnerOrgIsRefusedOnItsRow() {
        Draft draft = draft();

        JsonNode body = refused(draft, body(draft, List.of(), List.of(fixtures.orgId())));

        JsonNode error = body.path("errors").get(0);
        assertThat(error.path("field").asText()).isEqualTo("shareOrgIds[0]");
        assertThat(error.path("code").asText()).isEqualTo("LEARNING-INVALID_REFERENCE");
        assertThat(state(draft)).isEqualTo("draft");
        assertThat(shares(draft)).isZero();
    }

    @Test
    void anAlreadySharedOrgIsLeftUnchanged() {
        Draft draft = draft();
        api.ok(INSTRUCTOR, api.put(Map.of("orgId", sharedOrg, "includeDescendants", false, "canAssign", false,
            "canSelfEnroll", true, "active", true), "/api/v1/courses/{c}/shares", draft.course().id()));
        long events = count("select count(*) from learning_hub.audit_log where action = 'COURSE_SHARED' "
            + "and entity_id = ?", String.valueOf(draft.course().id()));

        publish(draft, body(draft, List.of(), List.of(sharedOrg)));

        assertThat(shares(draft)).isEqualTo(1);
        assertThat(jdbc.queryForMap("""
            select include_descendants, can_assign, can_self_enroll, status
            from learning_hub.course_shares where course_id = ? and org_id = ?
            """, draft.course().id(), sharedOrg))
            .containsEntry("include_descendants", false).containsEntry("can_assign", false)
            .containsEntry("can_self_enroll", true).containsEntry("status", "active");
        assertThat(count("select count(*) from learning_hub.audit_log where action = 'COURSE_SHARED' "
            + "and entity_id = ?", String.valueOf(draft.course().id()))).isEqualTo(events);
    }

    @Test
    void aDueDateBeforeTheStartIsRefusedOnItsRow() {
        Draft draft = draft();
        Map<String, Object> bad = userRow(LEARNER);
        bad.put("startAt", "2030-01-02T00:00:00Z");
        bad.put("dueAt", "2030-01-01T00:00:00Z");

        JsonNode body = refused(draft, body(draft, List.of(bad), List.of()));

        JsonNode error = body.path("errors").get(0);
        assertThat(error.path("field").asText()).isEqualTo("assignments[0]");
        assertThat(error.path("code").asText()).isEqualTo("OUT_OF_RANGE");
        assertThat(state(draft)).isEqualTo("draft");
        assertThat(assignments(draft)).isZero();
    }

    @Test
    void retryingTheSamePublishIsRefusedByTheVersionCheckAndNotDuplicated() {
        Draft draft = draft();
        Map<String, Object> request = body(draft, List.of(userRow(LEARNER)), List.of(sharedOrg));
        publish(draft, request);

        api.error(INSTRUCTOR, api.post(request, PUBLISH, draft.course().id(), draft.revisionId()), "LEARNING-CONFLICT");

        assertThat(assignments(draft)).isEqualTo(1);
        assertThat(shares(draft)).isEqualTo(1);
        assertThat(enrolled(draft, LEARNER)).isEqualTo(1);
    }
}
