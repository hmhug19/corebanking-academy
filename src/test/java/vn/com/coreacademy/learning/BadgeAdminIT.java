package vn.com.coreacademy.learning;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;

/** Quản lý huy hiệu: quyền, khóa luật khi đã có người nhận, ngừng dùng, audit và trao bù nền. */
class BadgeAdminIT extends AssessmentIntegrationTest {
    private static final String BADGES = "/api/v1/admin/learning/badges";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeEach
    void client() {
        api = new ApiClient(mvc, json);
        api.ok(ADMIN, get(BADGES));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from learning_hub.user_badges where badge_id in"
            + " (select badge_id from learning_hub.badge_definitions where code like 'CUSTOM\\_%')");
        jdbc.update("delete from learning_hub.badge_definitions where code like 'CUSTOM\\_%'");
    }

    private static Map<String, Object> body(String name, String rule, Integer n, Long program) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", "Mô tả " + name);
        body.put("icon", "award");
        body.put("ruleType", rule);
        body.put("paramN", n);
        body.put("paramProgramId", program);
        return body;
    }

    /** Một huy hiệu cần số lớn để chắc chắn chưa ai đủ điều kiện, nên trao bù xong không có người nhận. */
    private JsonNode createUnreachable(String name) {
        return api.ok(ADMIN, api.post(body(name, "STREAK_WORKING_DAYS", 999, null), BADGES));
    }

    private JsonNode waitUntilDone(long id) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            for (JsonNode row : api.ok(ADMIN, get(BADGES))) {
                if (row.path("badgeId").asLong() == id && !"RUNNING".equals(row.path("backfillStatus").asText())) {
                    return row;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("backfill did not finish");
    }

    @Test
    void onlyASystemAdminMayListCreateEditOrRetire() {
        long id = createUnreachable("Quyền").path("badgeId").asLong();

        api.http(INSTRUCTOR, get(BADGES), 403, "CORE-ACCESS_DENIED");
        api.http(LEARNER, api.post(body("X", "COURSES_COMPLETED", 1, null), BADGES), 403, "CORE-ACCESS_DENIED");
        api.http(INSTRUCTOR, api.put(body("X", "COURSES_COMPLETED", 1, null), BADGES + "/" + id), 403,
            "CORE-ACCESS_DENIED");
        api.http(LEARNER, api.post(Map.of(), BADGES + "/" + id + "/retire"), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void createReturnsAtOnceAndTheBackfillReachesDoneWithoutNotifying() throws Exception {
        int notices = jdbc.queryForObject("select count(*) from learning_hub.learning_reminders", Integer.class);

        JsonNode created = api.ok(ADMIN, api.post(body("Một khóa", "COURSES_COMPLETED", 1, null), BADGES));
        long id = created.path("badgeId").asLong();

        assertThat(created.path("code").asText()).startsWith("CUSTOM_");
        assertThat(created.path("status").asText()).isEqualTo("active");
        assertThat(created.path("condition").asText()).isNotBlank();
        JsonNode done = waitUntilDone(id);
        assertThat(done.path("backfillStatus").asText()).isEqualTo("DONE");
        assertThat(done.path("backfillDone").asInt()).isEqualTo(done.path("backfillTotal").asInt());
        assertThat(done.path("holders").asLong()).isEqualTo(done.path("backfillTotal").asLong());
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.learning_reminders", Integer.class))
            .as("a backfill is silent").isEqualTo(notices);
    }

    @Test
    void invalidRulesAreRefused() {
        api.error(ADMIN, api.post(body("A", "NOPE", 1, null), BADGES), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(body("A", "COURSES_COMPLETED", 0, null), BADGES), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(body("A", "COURSES_COMPLETED", 1001, null), BADGES), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(body("A", "PROGRAM_COMPLETED", null, 999_999_999L), BADGES),
            "CORE-VALIDATION_FAILED");
        Map<String, Object> badIcon = body("A", "COURSES_COMPLETED", 1, null);
        badIcon.put("icon", "Bad Icon!");
        api.error(ADMIN, api.post(badIcon, BADGES), "CORE-VALIDATION_FAILED");
    }

    @Test
    void aBadgeThatSomeoneHoldsHasItsRuleLockedButCanStillBeRenamed() {
        long id = createUnreachable("Khóa luật").path("badgeId").asLong();
        assertThat(api.ok(ADMIN, get(BADGES)).findValuesAsText("name")).contains("Khóa luật");
        jdbc.update("insert into learning_hub.user_badges (user_id, badge_id, source) values (?, ?, 'EVENT')",
            LEARNER, id);

        JsonNode row = null;
        for (JsonNode candidate : api.ok(ADMIN, get(BADGES))) {
            if (candidate.path("badgeId").asLong() == id) {
                row = candidate;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("locked").asBoolean()).isTrue();
        assertThat(row.path("holders").asLong()).isEqualTo(1);

        api.error(ADMIN, api.put(body("Khóa luật", "STREAK_WORKING_DAYS", 5, null), BADGES + "/" + id),
            "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.put(body("Khóa luật", "COURSES_COMPLETED", 1, null), BADGES + "/" + id),
            "CORE-VALIDATION_FAILED");

        JsonNode renamed = api.ok(ADMIN, api.put(body("Tên mới", "STREAK_WORKING_DAYS", 999, null),
            BADGES + "/" + id));
        assertThat(renamed.path("name").asText()).isEqualTo("Tên mới");
        assertThat(renamed.path("paramN").asInt()).isEqualTo(999);
    }

    @Test
    void anUnheldBadgeRuleCanChange() throws Exception {
        long id = createUnreachable("Đổi luật").path("badgeId").asLong();
        waitUntilDone(id);

        JsonNode changed = api.ok(ADMIN, api.put(body("Đổi luật", "STREAK_WORKING_DAYS", 998, null),
            BADGES + "/" + id));

        assertThat(changed.path("paramN").asInt()).isEqualTo(998);
        assertThat(waitUntilDone(id).path("backfillStatus").asText()).isEqualTo("DONE");
    }

    @Test
    void retiringKeepsHoldersAndIsRepeatableAndAudited() {
        long id = createUnreachable("Ngừng").path("badgeId").asLong();
        jdbc.update("insert into learning_hub.user_badges (user_id, badge_id, source) values (?, ?, 'EVENT')",
            LEARNER, id);

        JsonNode retired = api.ok(ADMIN, api.post(Map.of(), BADGES + "/" + id + "/retire"));
        JsonNode again = api.ok(ADMIN, api.post(Map.of(), BADGES + "/" + id + "/retire"));

        assertThat(retired.path("status").asText()).isEqualTo("retired");
        assertThat(retired.path("holders").asLong()).isEqualTo(1);
        assertThat(again.path("status").asText()).isEqualTo("retired");
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.user_badges where badge_id = ?",
            Integer.class, id)).as("holders keep the badge").isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.audit_log where action = 'BADGE_RETIRED'"
            + " and entity_id = ?", Integer.class, String.valueOf(id))).as("one audit entry").isEqualTo(1);
        api.error(ADMIN, api.post(Map.of(), BADGES + "/987654321/retire"), "LEARNING-NOT_FOUND");
    }

    @Test
    void createAndEditAreAudited() {
        long id = createUnreachable("Audit").path("badgeId").asLong();
        api.ok(ADMIN, api.put(body("Audit 2", "STREAK_WORKING_DAYS", 999, null), BADGES + "/" + id));

        assertThat(jdbc.queryForObject("select actor_id from learning_hub.audit_log where action = 'BADGE_CREATED'"
            + " and entity_id = ?", String.class, String.valueOf(id))).isEqualTo(ADMIN);
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.audit_log where action = 'BADGE_UPDATED'"
            + " and entity_id = ?", Integer.class, String.valueOf(id))).isEqualTo(1);
    }
}
