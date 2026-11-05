package vn.com.coreacademy.learning;

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
