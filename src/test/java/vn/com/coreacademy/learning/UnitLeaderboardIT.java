package vn.com.coreacademy.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;

/**
 * Bảng xếp hạng đơn vị: XP trung bình mỗi học viên đang hoạt động, tỷ lệ tham gia, ngưỡng 5 học viên, top 10 và đơn vị của
 * người gọi. Các đơn vị ULB-* do test tạo; đơn vị của LEARNER được bổ sung cho đủ 5 học viên.
 */
class UnitLeaderboardIT extends AssessmentIntegrationTest {
    private static final String URL = "/api/v1/me/learning/leaderboard/units";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private long callerOrg;

    @BeforeEach
    void clean() {
        api = new ApiClient(mvc, json);
        jdbc.execute("truncate learning_hub.xp_ledger");
        jdbc.update("delete from learning_hub.quiz_attempts where user_id like 'UL-%'");
        callerOrg = jdbc.queryForObject("select org_id from learning_hub.users where user_id = ?", Long.class,
            LEARNER);
        jdbc.update("""
            insert into learning_hub.gamification_config_versions (version_no, lesson_xp, quiz_pass_xp,
                first_try_bonus_xp, first_try_threshold_percent, community_xp, community_daily_cap)
            values (1, 5, 30, 20, 90, 10, 30) on conflict (version_no) do nothing
            """);
    }

    @AfterEach
    void remove() {
        jdbc.update("delete from learning_hub.quiz_attempts where user_id like 'UL-%'");
        jdbc.update("delete from learning_hub.user_roles where user_id like 'UL-%'");
        jdbc.update("delete from learning_hub.users where user_id like 'UL-%'");
        jdbc.update("delete from learning_hub.orgs where code like 'ULB-%'");
    }

    /** Đơn vị mới với count học viên (mã UL-<mã>-<i>); trả org_id. */
    private long unit(String code, int count) {
        jdbc.update("insert into learning_hub.orgs (org_type, code, name) values ('UNIT', ?, ?)", "ULB-" + code,
            "ULB Đơn vị " + code);
        long org = jdbc.queryForObject("select org_id from learning_hub.orgs where code = ?", Long.class,
            "ULB-" + code);
        learners(org, "UL-" + code + "-", count);
        return org;
    }

    private void learners(long org, String prefix, int count) {
        for (int i = 1; i <= count; i++) {
            String id = prefix + i;
            jdbc.update("""
                insert into learning_hub.users (user_id, user_name, full_name, org_id, auth_provider, password_hash)
                values (?, ?, ?, ?, 'LOCAL', 'x')
                """, id, id.toLowerCase(), "Học viên " + id, org);
            jdbc.update("""
                insert into learning_hub.user_roles (user_id, role_id, scope)
                select ?, role_id, 'OWN' from learning_hub.roles where code = 'LEARNER'
                """, id);
        }
    }

    private void xp(String user, int amount, String when) {
        jdbc.update("""
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                config_version_id)
            select ?, ?, now() - cast(? as interval), 'LESSON', ?, version_id
            from learning_hub.gamification_config_versions where version_no = 1
            """, user, amount, when, "ul-" + SEQUENCE.incrementAndGet());
    }

    /** Một lượt thi đã nộp, tính là ngày học đủ điều kiện. */
    private void learned(String user, String when) {
        jdbc.update("""
            insert into learning_hub.quiz_attempts (quiz_id, user_id, enrollment_id, attempt_no, status, started_at,
                deadline_at, submitted_at, graded_at, time_limit_minutes, pass_percent, scoring_mode, grace_seconds,
                total_questions, max_score, raw_score, score_percent, passed)
            values (1, ?, 0, ?, 'SUBMITTED', now() - cast(? as interval) - interval '1 hour',
                now() + interval '1 hour', now() - cast(? as interval), now(), 30, 70, 'ALL_OR_NOTHING', 0, 1, 100,
                50, 50, false)
            """, user, SEQUENCE.incrementAndGet(), when, when);
    }

    private JsonNode board(String user, String query) {
        return api.ok(user, get(URL + query));
    }

    private static List<String> names(JsonNode rows) {
        List<String> result = new ArrayList<>();
        rows.forEach(row -> result.add(row.path("unitName").asText()));
        return result;
    }

    private List<String> mine(JsonNode board) {
        return names(board.path("top")).stream().filter(name -> name.startsWith("ULB")).toList();
    }

    /** Đơn vị của LEARNER có 5 học viên: LEARNER và 4 người thêm. */
    private void fillCallerUnit() {
        learners(callerOrg, "UL-CALLER-", 2);
        // Demo seed has three learners in this unit, so two more make five.
    }

    @Test
    void unitsAreRankedByAverageXpPerActiveLearnerWithTheParticipationRate() {
        fillCallerUnit();
        unit("A", 5);
        unit("B", 5);
        xp("UL-A-1", 100, "0 days");
        xp("UL-A-2", 50, "0 days");
        learned("UL-A-1", "0 days");
        learned("UL-A-2", "0 days");
        xp("UL-B-1", 60, "0 days");
        learned("UL-B-1", "0 days");
        xp(LEARNER, 10, "0 days");

        JsonNode board = board(LEARNER, "");

        assertThat(board.has("page")).as("no paging").isFalse();
        assertThat(board.path("period").asText()).isEqualTo("month");
        JsonNode a = board.path("top").get(0);
        assertThat(a.path("unitName").asText()).isEqualTo("ULB Đơn vị A");
        assertThat(a.path("rank").asInt()).isEqualTo(1);
        assertThat(a.path("averageXp").asDouble()).as("(100 + 50) / 2 active learners").isEqualTo(75.0);
        assertThat(a.path("participationPercent").asInt()).as("2 of 5 learned").isEqualTo(40);
        JsonNode b = board.path("top").get(1);
        assertThat(b.path("averageXp").asDouble()).isEqualTo(60.0);
        assertThat(b.path("participationPercent").asInt()).isEqualTo(20);
        assertThat(board.path("top").get(2).path("averageXp").asDouble()).isEqualTo(10.0);
        assertThat(board.path("myUnit").path("mine").asBoolean()).isTrue();
        assertThat(board.path("myUnit").path("rank").asInt()).isEqualTo(3);
        assertThat(board.path("myUnit").path("orgId").asLong()).isEqualTo(callerOrg);
        assertThat(board.path("myUnitTooSmall").asBoolean()).isFalse();
        assertThat(board.path("top").findValuesAsText("mine")).containsExactly("false", "false", "true");
    }

    @Test
    void aUnitWithFewerThanFiveLearnersIsNotRankedAndItsMembersAreToldSo() {
        fillCallerUnit();
        unit("A", 5);
        unit("SMALL", 4);
        xp("UL-A-1", 40, "0 days");
        xp("UL-SMALL-1", 500, "0 days");

        assertThat(mine(board(LEARNER, ""))).as("the small unit is left out however well it does")
            .containsExactly("ULB Đơn vị A");
        JsonNode small = board("UL-SMALL-1", "");
        assertThat(small.path("myUnit").isNull()).isTrue();
        assertThat(small.path("myUnitTooSmall").asBoolean()).isTrue();
        assertThat(mine(small)).containsExactly("ULB Đơn vị A");
    }

    @Test
    void theCallerOfAnUnitWithFiveLearnersIsRanked() {
        unit("FIVE", 5);
        xp("UL-FIVE-1", 30, "0 days");

        JsonNode five = board("UL-FIVE-1", "");

        assertThat(five.path("myUnit").path("unitName").asText()).isEqualTo("ULB Đơn vị FIVE");
        assertThat(five.path("myUnit").path("averageXp").asDouble()).isEqualTo(30.0);
        assertThat(five.path("myUnitTooSmall").asBoolean()).isFalse();
    }

    @Test
    void theSamePeriodFilterAppliesAndAnEarlierMonthIsReadByOffset() {
        unit("A", 5);
        xp("UL-A-1", 90, "1 month");
        learned("UL-A-1", "1 month");
        xp("UL-A-2", 20, "0 days");

        JsonNode now = board("UL-A-1", "?period=month");
        JsonNode before = board("UL-A-1", "?period=month&offset=1");
        JsonNode all = board("UL-A-1", "?period=all");

        assertThat(now.path("myUnit").path("averageXp").asDouble()).isEqualTo(20.0);
        assertThat(now.path("myUnit").path("participationPercent").asInt()).isZero();
        assertThat(before.path("myUnit").path("averageXp").asDouble()).isEqualTo(90.0);
        assertThat(before.path("myUnit").path("participationPercent").asInt()).isEqualTo(20);
        assertThat(before.path("offset").asInt()).isEqualTo(1);
        assertThat(before.path("from").isNull()).isFalse();
        assertThat(all.path("myUnit").path("averageXp").asDouble()).as("(90 + 20) / 2").isEqualTo(55.0);
        assertThat(all.path("from").isNull()).isTrue();
        assertThat(board("UL-A-1", "?period=week").path("period").asText()).isEqualTo("week");
    }

    @Test
    void onlyTheTopTenAreReturnedAndTheCallerKeepsTheirOwnUnitWithoutShowingTheBottom() {
        for (int i = 1; i <= 11; i++) {
            unit("T" + i, 5);
            xp("UL-T" + i + "-1", 100 + i, "0 days");
        }
        unit("LOW", 5);

        JsonNode board = board("UL-LOW-1", "");

        assertThat(board.path("top")).hasSize(10);
        assertThat(board.path("top").get(0).path("averageXp").asDouble()).isEqualTo(111.0);
        assertThat(board.path("top").findValuesAsText("mine")).doesNotContain("true");
        assertThat(board.path("myUnit").path("unitName").asText()).isEqualTo("ULB Đơn vị LOW");
        assertThat(board.path("myUnit").path("rank").asInt()).isGreaterThan(10);
        assertThat(names(board.path("top"))).as("the weakest of the eleven is not shown")
            .doesNotContain("ULB Đơn vị T1");
    }

    @Test
    void tiedUnitsShareARank() {
        unit("A", 5);
        unit("B", 5);
        xp("UL-A-1", 40, "0 days");
        xp("UL-B-1", 40, "0 days");

        JsonNode board = board("UL-A-1", "");
        List<Integer> ranks = new ArrayList<>();
        board.path("top").forEach(row -> {
            if (row.path("unitName").asText().startsWith("ULB")) {
                ranks.add(row.path("rank").asInt());
            }
        });

        assertThat(ranks).hasSize(2);
        assertThat(ranks.get(0)).isEqualTo(ranks.get(1));
    }

    @Test
    void aLearnerWhoHidesTheirNameStillCountsForTheirUnit() {
        unit("A", 5);
        xp("UL-A-1", 100, "0 days");
        xp("UL-A-2", 50, "0 days");
        learned("UL-A-1", "0 days");
        JsonNode before = board(LEARNER, "").path("top").get(0);
        jdbc.update("insert into learning_hub.learner_preferences (user_id, leaderboard_hidden) values (?, true)",
            "UL-A-1");

        try {
            JsonNode after = board(LEARNER, "").path("top").get(0);

            assertThat(after.path("averageXp").asDouble()).isEqualTo(before.path("averageXp").asDouble())
                .isEqualTo(75.0);
            assertThat(after.path("participationPercent").asInt()).isEqualTo(20);
        } finally {
            jdbc.update("delete from learning_hub.learner_preferences where user_id = 'UL-A-1'");
        }
    }

    @Test
    void signedInNonLearnersCanReadAndBadParametersAreRejected() {
        assertThat(api.ok(ADMIN, get(URL)).path("top").isArray()).isTrue();
        api.http(null, get(URL), 401, "CORE-UNAUTHENTICATED");
        api.error(LEARNER, get(URL + "?period=year"), "CORE-VALIDATION_FAILED");
        api.error(LEARNER, get(URL + "?offset=-1"), "CORE-VALIDATION_FAILED");
        api.error(LEARNER, get(URL + "?offset=521"), "CORE-VALIDATION_FAILED");
    }
}
