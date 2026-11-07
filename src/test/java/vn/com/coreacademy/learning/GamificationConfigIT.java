package vn.com.coreacademy.learning;

import java.util.List;
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
import vn.com.coreacademy.learning.service.XpLedgerService;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;

/** Cấu hình gamification: quyền, phiên bản bất biến, bậc thang, lịch ngày làm việc và audit. */
class GamificationConfigIT extends AssessmentIntegrationTest {
    private static final String CONFIG = "/api/v1/admin/learning/gamification/config";
    private static final String VERSIONS = CONFIG + "/versions";
    private static final String CALENDAR = "/api/v1/admin/learning/working-calendar/";
    private static final int YEAR = 2031;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private XpLedgerService xp;

    private ApiClient api;

    @BeforeEach
    void client() {
        api = new ApiClient(mvc, json);
        xp.currentConfig();
    }

    /** Trả cấu hình về mặc định để các test khác vẫn dùng trọng số và bậc thang ban đầu. */
    @AfterEach
    void restoreDefaults() {
        api.ok(ADMIN, api.post(config(5, 30, 20, 90, 10, 30, defaultLadder()), CONFIG));
        jdbc.update("delete from learning_hub.working_calendar_days where calendar_date >= ? and calendar_date < ?",
            java.sql.Date.valueOf(YEAR + "-01-01"), java.sql.Date.valueOf((YEAR + 1) + "-01-01"));
    }

    @Test
    void aSaveAddsAnImmutableVersionThatAppliesOnlyForward() {
        int before = api.ok(ADMIN, get(CONFIG)).path("versionNo").asInt();
        int rowsBefore = jdbc.queryForObject("select count(*) from learning_hub.xp_ledger", Integer.class);
        long amountsBefore = jdbc.queryForObject("select coalesce(sum(amount), 0) from learning_hub.xp_ledger",
            Long.class);

        JsonNode saved = api.ok(ADMIN, api.post(config(8, 40, 25, 80, 12, 36, defaultLadder()), CONFIG));

        assertThat(saved.path("versionNo").asInt()).isEqualTo(before + 1);
        assertThat(saved.path("lessonXp").asInt()).isEqualTo(8);
        assertThat(saved.path("quizPassXp").asInt()).isEqualTo(40);
        assertThat(saved.path("firstTryBonusXp").asInt()).isEqualTo(25);
        assertThat(saved.path("firstTryThresholdPercent").asInt()).isEqualTo(80);
        assertThat(saved.path("communityXp").asInt()).isEqualTo(12);
        assertThat(saved.path("communityDailyCap").asInt()).isEqualTo(36);
        assertThat(saved.path("createdBy").asText()).isEqualTo(ADMIN);
        assertThat(saved.path("current").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.xp_ledger", Integer.class))
            .as("saving a config writes no XP").isEqualTo(rowsBefore);
        assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0) from learning_hub.xp_ledger", Long.class))
            .as("past XP is untouched").isEqualTo(amountsBefore);

        xp.lessonCompleted("CFG-FWD", 987_001L, java.time.Instant.now());
        Map<String, Object> row = jdbc.queryForMap("""
            select l.amount, v.version_no from learning_hub.xp_ledger l
            join learning_hub.gamification_config_versions v on v.version_id = l.config_version_id
            where l.user_id = 'CFG-FWD'
            """);
        assertThat(((Number) row.get("amount")).intValue()).as("XP earned after the save uses the new weight")
            .isEqualTo(8);
        assertThat(((Number) row.get("version_no")).intValue()).isEqualTo(before + 1);
    }

    @Test
    void theHistoryListsEveryVersionNewestFirstWithItsAuthorAndTime() {
        api.ok(ADMIN, api.post(config(6, 30, 20, 90, 10, 30, defaultLadder()), CONFIG));

        JsonNode versions = api.ok(ADMIN, get(VERSIONS));

        assertThat(versions.size()).isGreaterThanOrEqualTo(2);
        assertThat(versions.get(0).path("current").asBoolean()).isTrue();
        assertThat(versions.get(0).path("lessonXp").asInt()).isEqualTo(6);
        assertThat(versions.get(0).path("createdBy").asText()).isEqualTo(ADMIN);
        assertThat(versions.get(0).path("createdAt").asText()).isNotBlank();
        assertThat(versions.get(1).path("current").asBoolean()).isFalse();
        for (int i = 1; i < versions.size(); i++) {
            assertThat(versions.get(i).path("versionNo").asInt())
                .isLessThan(versions.get(i - 1).path("versionNo").asInt());
            assertThat(versions.get(i).path("levels").size()).isGreaterThan(0);
        }
    }

    @Test
    void everySaveIsInTheAuditLogWithItsAuthor() {
        JsonNode saved = api.ok(ADMIN, api.post(config(7, 30, 20, 90, 10, 30, defaultLadder()), CONFIG));

        Map<String, Object> audit = jdbc.queryForMap("""
            select actor_id, details::text as details from learning_hub.audit_log
            where action = 'GAMIFICATION_CONFIG_SAVED' and entity_id = ?
            """, String.valueOf(saved.path("versionNo").asInt()));
        assertThat(audit.get("actor_id")).isEqualTo(ADMIN);
        assertThat((String) audit.get("details")).contains("lessonXp");
    }

    @Test
    void onlyASystemAdminMayReadOrSave() {
        api.http(INSTRUCTOR, get(CONFIG), 403, "CORE-ACCESS_DENIED");
        api.http(LEARNER, get(VERSIONS), 403, "CORE-ACCESS_DENIED");
        api.http(INSTRUCTOR, api.post(config(9, 30, 20, 90, 10, 30, defaultLadder()), CONFIG), 403,
            "CORE-ACCESS_DENIED");
        api.http(LEARNER, get(CALENDAR + YEAR), 403, "CORE-ACCESS_DENIED");
        api.http(INSTRUCTOR, api.put(Map.of("days", List.of()), CALENDAR + YEAR), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void aBadLadderIsRefusedAndNothingIsSaved() {
        int versions = api.ok(ADMIN, get(VERSIONS)).size();

        api.error(ADMIN, api.post(config(5, 30, 20, 90, 10, 30, List.of(level("A", 10), level("B", 100))), CONFIG),
            "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(config(5, 30, 20, 90, 10, 30, List.of(level("A", 0), level("B", 100),
            level("C", 100))), CONFIG), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(config(5, 30, 20, 90, 10, 30, List.of(level("A", 0), level("B", 100),
            level("C", 50))), CONFIG), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(config(5, 30, 20, 90, 10, 30, List.of(level("A", 0), level("  ", 100))), CONFIG),
            "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(config(5, 30, 20, 101, 10, 30, defaultLadder()), CONFIG),
            "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(config(-1, 30, 20, 90, 10, 30, defaultLadder()), CONFIG),
            "CORE-VALIDATION_FAILED");

        assertThat(api.ok(ADMIN, get(VERSIONS)).size()).isEqualTo(versions);
    }

    @Test
    void raisingTheLadderKeepsTheLevelsAlreadyReached() {
        String user = "CFG-LVL";
        jdbc.update("""
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                config_version_id)
            select ?, 650, now(), 'ADJUSTMENT', 'cfg-' || gen_random_uuid(), max(version_id)
            from learning_hub.gamification_config_versions
            """, user);

        api.ok(ADMIN, api.post(config(5, 30, 20, 90, 10, 30, List.of(level("Cấp 1", 0), level("Cấp 2", 1000),
            level("Cấp 3", 3000))), CONFIG));

        assertThat(jdbc.queryForObject("select level_no from learning_hub.learner_levels where user_id = ?",
            Integer.class, user)).as("level 3 was reached on the old ladder and stays").isEqualTo(3);
    }

    @Test
    void theCalendarYearIsReplacedAndValidated() {
        List<Map<String, Object>> days = List.of(
            Map.of("date", YEAR + "-02-01", "type", "HOLIDAY", "note", "Tết thử"),
            Map.of("date", YEAR + "-02-08", "type", "MAKEUP_WORKDAY", "note", "  Làm bù  "));

        JsonNode saved = api.ok(ADMIN, api.put(Map.of("days", days), CALENDAR + YEAR));

        assertThat(saved.size()).isEqualTo(2);
        assertThat(saved.get(0).path("date").asText()).isEqualTo(YEAR + "-02-01");
        assertThat(saved.get(0).path("type").asText()).isEqualTo("HOLIDAY");
        assertThat(saved.get(1).path("note").asText()).isEqualTo("Làm bù");

        JsonNode replaced = api.ok(ADMIN, api.put(Map.of("days", List.of(
            Map.of("date", YEAR + "-03-03", "type", "HOLIDAY"))), CALENDAR + YEAR));
        assertThat(replaced.size()).as("the year is replaced, not merged").isEqualTo(1);
        assertThat(api.ok(ADMIN, get(CALENDAR + YEAR)).size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select created_by from learning_hub.working_calendar_days"
            + " where calendar_date = ?", String.class, java.sql.Date.valueOf(YEAR + "-03-03"))).isEqualTo(ADMIN);
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.audit_log"
            + " where action = 'WORKING_CALENDAR_SAVED' and entity_id = ?", Integer.class, String.valueOf(YEAR)))
            .isGreaterThanOrEqualTo(2);

        api.error(ADMIN, api.put(Map.of("days", List.of(Map.of("date", (YEAR + 1) + "-01-01", "type", "HOLIDAY"))),
            CALENDAR + YEAR), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.put(Map.of("days", List.of(Map.of("date", YEAR + "-04-01", "type", "SUNDAY"))),
            CALENDAR + YEAR), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.put(Map.of("days", List.of(Map.of("date", YEAR + "-04-01", "type", "HOLIDAY"),
            Map.of("date", YEAR + "-04-01", "type", "MAKEUP_WORKDAY"))), CALENDAR + YEAR),
            "CORE-VALIDATION_FAILED");
        assertThat(api.ok(ADMIN, get(CALENDAR + YEAR)).size()).as("a refused save changes nothing").isEqualTo(1);
    }

    private static Map<String, Object> level(String name, int minXp) {
        return Map.of("name", name, "minXp", minXp);
    }

    private static List<Map<String, Object>> defaultLadder() {
        return List.of(level("Giao dịch viên tập sự", 0), level("Giao dịch viên", 200), level("Chuyên viên", 600),
            level("Chuyên viên chính", 1500), level("Chuyên gia nghiệp vụ", 3500),
            level("Chuyên gia Core Banking", 7000));
    }

    private static Map<String, Object> config(int lesson, int quiz, int bonus, int threshold, int community,
                                              int cap, List<Map<String, Object>> levels) {
        return Map.of("lessonXp", lesson, "quizPassXp", quiz, "firstTryBonusXp", bonus,
            "firstTryThresholdPercent", threshold, "communityXp", community, "communityDailyCap", cap,
            "levels", levels);
    }
}
