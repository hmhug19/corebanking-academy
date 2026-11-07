package vn.com.coreacademy.learning;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/** Điều chỉnh XP thủ công: quyền, lý do bắt buộc, dòng bù trừ thêm vào sổ, audit và phần "Điều chỉnh" của học viên. */
class XpAdjustmentIT extends AssessmentIntegrationTest {
    private static final String URL = "/api/v1/admin/learning/xp-adjustments";

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
    }

    @Test
    void aSystemAdminWritesACompensatingRowAndAnAuditEntryAndTheLearnerSeesIt() {
        long before = total(LEARNER_2);
        int rowsBefore = rows(LEARNER_2);
        long untouchedSum = jdbc.queryForObject("""
            select coalesce(sum(amount), 0) from learning_hub.xp_ledger where user_id = ? and source_kind <> 'ADJUSTMENT'
            """, Long.class, LEARNER_2);

        JsonNode result = api.ok(ADMIN, api.post(Map.of("userId", LEARNER_2, "amount", 40,
            "reason", "  Cộng bù do lỗi đồng bộ  "), URL));

        assertThat(result.path("userId").asText()).isEqualTo(LEARNER_2);
        assertThat(result.path("amount").asInt()).isEqualTo(40);
        assertThat(result.path("total").asLong()).isEqualTo(before + 40);
        assertThat(rows(LEARNER_2)).as("one extra row").isEqualTo(rowsBefore + 1);
        assertThat(jdbc.queryForObject("""
            select coalesce(sum(amount), 0) from learning_hub.xp_ledger where user_id = ? and source_kind <> 'ADJUSTMENT'
            """, Long.class, LEARNER_2)).as("existing rows are untouched").isEqualTo(untouchedSum);
        assertThat(jdbc.queryForObject("""
            select reason from learning_hub.xp_ledger where entry_id = ?
            """, String.class, result.path("entryId").asLong())).isEqualTo("Cộng bù do lỗi đồng bộ");

        Map<String, Object> audit = jdbc.queryForMap("""
            select actor_id, action, entity_type, details::text as details from learning_hub.audit_log
            where entity_id = ? and action = 'XP_ADJUSTED'
            """, String.valueOf(result.path("entryId").asLong()));
        assertThat(audit.get("actor_id")).isEqualTo(ADMIN);
        assertThat((String) audit.get("details")).contains(LEARNER_2, "Cộng bù do lỗi đồng bộ", "40");

        JsonNode mine = api.ok(LEARNER_2, get("/api/v1/me/learning/points"));
        assertThat(mine.path("parts").path("adjustments").asLong()).isGreaterThanOrEqualTo(40);
        assertThat(mine.path("total").asLong()).isEqualTo(before + 40);
    }

    @Test
    void aNegativeCorrectionIsAllowedUntilItWouldMakeTheTotalNegative() {
        api.ok(ADMIN, api.post(Map.of("userId", LEARNER, "amount", 25, "reason", "Thêm để trừ"), URL));
        long total = total(LEARNER);

        api.ok(ADMIN, api.post(Map.of("userId", LEARNER, "amount", -25, "reason", "Trừ do gian lận"), URL));
        assertThat(total(LEARNER)).isEqualTo(total - 25);

        api.error(ADMIN, api.post(Map.of("userId", LEARNER, "amount", -(total(LEARNER) + 1), "reason", "Quá tay"),
            URL), "CORE-VALIDATION_FAILED");
        assertThat(total(LEARNER)).isEqualTo(total - 25);
    }

    @Test
    void aMissingOrBlankReasonAndAZeroAmountAreRefused() {
        int rows = rows(LEARNER_2);

        api.error(ADMIN, api.post(Map.of("userId", LEARNER_2, "amount", 5), URL), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(Map.of("userId", LEARNER_2, "amount", 5, "reason", "   "), URL),
            "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(Map.of("userId", LEARNER_2, "amount", 0, "reason", "Không đổi"), URL),
            "CORE-VALIDATION_FAILED");
        assertThat(rows(LEARNER_2)).isEqualTo(rows);
    }

    @Test
    void onlyASystemAdminMayCorrect() {
        int rows = rows(LEARNER_2);

        api.http(INSTRUCTOR, api.post(Map.of("userId", LEARNER_2, "amount", 5, "reason", "x"), URL), 403,
            "CORE-ACCESS_DENIED");
        api.http(LEARNER, api.post(Map.of("userId", LEARNER_2, "amount", 5, "reason", "x"), URL), 403,
            "CORE-ACCESS_DENIED");
        assertThat(rows(LEARNER_2)).isEqualTo(rows);
    }

    private long total(String user) {
        return api.ok(user, get("/api/v1/me/learning/points")).path("total").asLong();
    }

    private int rows(String user) {
        return jdbc.queryForObject("select count(*) from learning_hub.xp_ledger where user_id = ?", Integer.class,
            user);
    }
}
