package vn.com.coreacademy.learning;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/** Báo cáo tác động: quyền, phạm vi đơn vị do server chốt, mốc ra mắt, số liệu tổng hợp và CSV khớp báo cáo. */
class GamificationImpactIT extends AssessmentIntegrationTest {
    private static final String REPORT = "/api/v1/reports/learning/gamification-impact";
    private static final AtomicLong LESSONS = new AtomicLong(9_100_000L);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private XpLedgerService xp;

    private ApiClient api;
    private Long originalOrg;
    private Long otherOrg;

    @BeforeEach
    void client() {
        api = new ApiClient(mvc, json);
        xp.currentConfig();
        originalOrg = jdbc.queryForObject("select org_id from learning_hub.users where user_id = ?", Long.class,
            LEARNER_2);
    }

    @AfterEach
    void restore() {
        jdbc.update("update learning_hub.users set org_id = ? where user_id = ?", originalOrg, LEARNER_2);
        jdbc.update("delete from learning_hub.learner_preferences where user_id in (?, ?)", LEARNER, LEARNER_2);
        if (otherOrg != null) {
            jdbc.update("delete from learning_hub.orgs where org_id = ?", otherOrg);
        }
    }

    private void earn(String user) {
        xp.lessonCompleted(user, LESSONS.incrementAndGet(), Instant.now());
    }

    private long moveSecondLearnerToAnotherOrg() {
        otherOrg = jdbc.queryForObject("""
            insert into learning_hub.orgs (parent_id, org_type, code, name, status, sort_no)
            values (null, 'UNIT', ?, 'Đơn vị khác', 'active', 1) returning org_id
            """, Long.class, "IMPACT" + System.nanoTime() % 100_000);
        jdbc.update("update learning_hub.users set org_id = ? where user_id = ?", otherOrg, LEARNER_2);
        return otherOrg;
    }

    private JsonNode section(JsonNode report, String field) {
        return report.path(field);
    }

    @Test
    void onlyReportRolesMayReadAndALearnerIsRefused() {
        api.http(LEARNER, get(REPORT), 403, "CORE-ACCESS_DENIED");
        api.http(LEARNER, get(REPORT + "/export"), 403, "CORE-ACCESS_DENIED");
        api.ok(ADMIN, get(REPORT));
        api.ok(INSTRUCTOR, get(REPORT));
    }

    @Test
    void theReportCountsLearnersXpBySourceAndWeeklyActivity() {
        earn(LEARNER);
        earn(LEARNER);

        JsonNode report = api.ok(ADMIN, get(REPORT));

        assertThat(report.path("learners").asInt()).isGreaterThanOrEqualTo(1);
        long lessonXp = 0;
        for (JsonNode source : section(report, "xpBySource")) {
            if ("LESSON".equals(source.path("source").asText())) {
                lessonXp = source.path("xp").asLong();
            }
        }
        assertThat(lessonXp).isGreaterThanOrEqualTo(10);
        assertThat(section(report, "weeklyActive").size()).isGreaterThanOrEqualTo(1);
        assertThat(section(report, "weeklyActive").get(0).path("learners").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(section(report, "levels").size()).isGreaterThanOrEqualTo(2);
        int perLevel = 0;
        for (JsonNode level : section(report, "levels")) {
            perLevel += level.path("learners").asInt();
        }
        assertThat(perLevel).as("every learner is at exactly one level").isEqualTo(report.path("learners").asInt());
    }

    @Test
    void theLaunchDateIsWhenConfigVersionOneWasSaved() {
        Instant launched = jdbc.queryForObject("select create_time from learning_hub.gamification_config_versions"
            + " where version_no = 1", java.sql.Timestamp.class).toInstant();

        JsonNode report = api.ok(ADMIN, get(REPORT));

        assertThat(Instant.parse(report.path("launchedAt").asText())).isEqualTo(launched);
        JsonNode comparison = report.path("comparison");
        assertThat(Instant.parse(comparison.path("launchedAt").asText())).isEqualTo(launched);
        assertThat(comparison.path("windowDays").asInt()).isEqualTo(28);
        assertThat(comparison.path("before").path("to").asText()).isNotBlank();
    }

    @Test
    void theUnitFilterAndTheCallersScopeNarrowTheLearners() {
        long other = moveSecondLearnerToAnotherOrg();
        int everyone = api.ok(ADMIN, get(REPORT)).path("learners").asInt();

        JsonNode inOther = api.ok(ADMIN, get(REPORT + "?orgId=" + other));
        JsonNode inFirst = api.ok(ADMIN, get(REPORT + "?orgId=" + originalOrg));
        JsonNode instructor = api.ok(INSTRUCTOR, get(REPORT));

        assertThat(inOther.path("learners").asInt()).isEqualTo(1);
        assertThat(inFirst.path("learners").asInt()).isEqualTo(everyone - 1);
        assertThat(instructor.path("learners").asInt())
            .as("an instructor scoped to the first unit never sees the other unit").isEqualTo(everyone - 1);
        assertThat(api.ok(INSTRUCTOR, get(REPORT + "?orgId=" + other)).path("learners").asInt())
            .as("asking for a unit outside the scope gives nothing").isZero();
    }

    @Test
    void theOptOutRateCountsHiddenLearnersInTheScope() {
        jdbc.update("insert into learning_hub.learner_preferences (user_id, leaderboard_hidden) values (?, true)"
            + " on conflict (user_id) do update set leaderboard_hidden = true", LEARNER);

        JsonNode optOut = api.ok(ADMIN, get(REPORT)).path("optOut");

        assertThat(optOut.path("hidden").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(optOut.path("ratePercent").asDouble()).isGreaterThan(0);
    }

    @Test
    void aBadPeriodIsRefused() {
        api.error(ADMIN, get(REPORT + "?from=2026-05-02&to=2026-05-01"), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, get(REPORT + "?from=2020-01-01&to=2026-05-01"), "CORE-VALIDATION_FAILED");
    }

    @Test
    void theCsvHoldsTheSameFiguresAsTheReportAndNoPerPersonRow() throws Exception {
        earn(LEARNER);
        JsonNode report = api.ok(ADMIN, get(REPORT));

        String csv = api.raw(ADMIN, get(REPORT + "/export"))
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv).isNotBlank();
        List<String> lines = csv.lines().toList();
        assertThat(lines.get(0)).contains("Nhóm").contains("Chỉ số").contains("Giá trị");
        assertThat(csv).contains("\"Số học viên\",\"" + report.path("learners").asInt() + "\"");
        assertThat(csv).doesNotContain(LEARNER).doesNotContain("Họ tên");
    }
}
