package vn.com.coreacademy.learning;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/**
 * Bảng xếp hạng trên ba học viên của bộ seed Identity (cùng một đơn vị, ba nhóm khác nhau) cùng một học viên ở đơn
 * vị khác. Điểm được dựng bằng dòng sổ XP, nên mỗi ca xóa sổ XP và nguồn điểm của các test khác trong database dùng chung.
 */
class LearningLeaderboardIT extends AssessmentIntegrationTest {
    private static final String URL = "/api/v1/me/learning/leaderboard";
    private static final String OUTSIDER = "900001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeEach
    void cleanPointsAndAddALearnerOfAnotherOrg() {
        api = new ApiClient(mvc, json);
        jdbc.execute("truncate learning_hub.xp_ledger");
        jdbc.update("delete from learning_hub.lesson_progress");
        jdbc.update("delete from learning_hub.quiz_attempts");
        jdbc.update("update learning_hub.course_enrollments set status = 'in_progress' where status = 'completed'");
        jdbc.update("""
            insert into learning_hub.orgs (org_type, code, name) values ('UNIT', 'LB-OTHER', 'Đơn vị khác')
            """);
        jdbc.update("""
            insert into learning_hub.users (user_id, user_name, full_name, org_id, auth_provider, password_hash)
            select ?, 'lb.other', 'Học viên đơn vị khác', org_id, 'LOCAL', 'x'
            from learning_hub.orgs where code = 'LB-OTHER'
            """, OUTSIDER);
        jdbc.update("""
            insert into learning_hub.user_roles (user_id, role_id, scope)
            select ?, role_id, 'OWN' from learning_hub.roles where code = 'LEARNER'
            """, OUTSIDER);
    }

    @AfterEach
    void removeTheOtherOrg() {
        jdbc.update("delete from learning_hub.user_roles where user_id = ?", OUTSIDER);
        jdbc.update("delete from learning_hub.users where user_id = ?", OUTSIDER);
        jdbc.update("delete from learning_hub.orgs where code = 'LB-OTHER'");
    }

    @Test
    void orgIsTheDefaultAndRanksTiesTogetherWithZeroPointUsersLast() {
        days(LEARNER, 3);
        days(LEARNER_2, 3);

        JsonNode board = api.ok(LEARNER, get(URL));
        JsonNode items = board.path("page").path("items");

        assertThat(board.path("page").path("totalElements").asInt()).as("three learners of the unit").isEqualTo(3);
        assertThat(ids(items)).containsExactlyInAnyOrder(LEARNER, LEARNER_2, "000005");
        assertThat(ranks(items)).as("1, 1, 3").containsExactly(1, 1, 3);
        assertThat(points(items)).containsExactly(30L, 30L, 0L);
        assertThat(items.get(2).path("userId").asText()).as("the zero-point user is last").isEqualTo("000005");
        assertThat(items.get(0).path("streakDays").asInt()).as("no lesson done, so no working-day streak").isZero();
        assertThat(items.get(0).path("teamName").asText()).isNotBlank();
        assertThat(names(items.get(0), items.get(1))).as("ties are ordered by name")
            .isSortedAccordingTo(Collator.getInstance(Locale.forLanguageTag("vi")));
        assertThat(board.path("page").path("page").asInt()).isZero();
        assertThat(board.path("page").path("size").asInt()).isEqualTo(20);
    }

    @Test
    void scopesLimitWhoIsRanked() {
        days(LEARNER, 2);

        assertThat(ids(api.ok(LEARNER, get(URL + "?scope=team")).path("page").path("items")))
            .as("the caller's own team only").containsExactly(LEARNER);
        assertThat(ids(api.ok(LEARNER, get(URL + "?scope=org")).path("page").path("items")))
            .containsExactlyInAnyOrder(LEARNER, LEARNER_2, "000005");
        assertThat(ids(api.ok(LEARNER, get(URL + "?scope=all")).path("page").path("items")))
            .as("every learner of the system").containsExactlyInAnyOrder(LEARNER, LEARNER_2, "000005", OUTSIDER);
        assertThat(ids(api.ok(LEARNER, get(URL + "?scope=all&size=100")).path("page").path("items")))
            .hasSize(4);
    }

    @Test
    void notEveryoneActiveAndEligibleIsRanked() {
        jdbc.update("update learning_hub.users set status = 'inactive' where user_id = '000005'");
        try {
            assertThat(ids(api.ok(LEARNER, get(URL)).path("page").path("items")))
                .as("an inactive learner is not ranked").containsExactlyInAnyOrder(LEARNER, LEARNER_2);
        } finally {
            jdbc.update("update learning_hub.users set status = 'active' where user_id = '000005'");
        }
        // Quản trị viên không có vai trò học viên nên không có trong bảng dù cùng đơn vị.
        assertThat(ids(api.ok(LEARNER, get(URL)).path("page").path("items"))).doesNotContain("000001", "000002",
            "000003");
    }

    @Test
    void meIsPresentEvenWhenOffThePage() {
        days(LEARNER, 3);
        days(LEARNER_2, 2);

        JsonNode board = api.ok(LEARNER, get(URL + "?size=1&page=2"));

        assertThat(ids(board.path("page").path("items"))).containsExactly("000005");
        assertThat(board.path("page").path("totalElements").asInt()).isEqualTo(3);
        assertThat(board.path("page").path("totalPages").asInt()).isEqualTo(3);
        assertThat(board.path("me").path("userId").asText()).isEqualTo(LEARNER);
        assertThat(board.path("me").path("me").asBoolean()).isTrue();
        assertThat(board.path("me").path("rank").asInt()).isEqualTo(1);
        assertThat(board.path("me").path("points").asLong()).isEqualTo(30);
        assertThat(board.path("page").path("items").get(0).path("me").asBoolean()).isFalse();
        assertThat(api.ok(LEARNER, get(URL + "?size=1&page=9")).path("page").path("items")).isEmpty();
    }

    @Test
    void theRankOfMeComesFromTheSelectedScope() {
        days(LEARNER, 1);
        days(OUTSIDER, 5);

        assertThat(api.ok(LEARNER, get(URL + "?scope=org")).path("me").path("rank").asInt()).isEqualTo(1);
        assertThat(api.ok(LEARNER, get(URL + "?scope=all")).path("me").path("rank").asInt()).isEqualTo(2);
    }

    @Test
    void aCallerWithoutATeamGetsAnEmptyTeamPageAndTheirOwnRow() {
        days(LEARNER_2, 2);
        jdbc.update("update learning_hub.users set team_id = null where user_id = ?", LEARNER_2);
        try {
            JsonNode board = api.ok(LEARNER_2, get(URL + "?scope=team"));

            assertThat(board.path("page").path("items")).isEmpty();
            assertThat(board.path("page").path("totalElements").asInt()).isZero();
            assertThat(board.path("me").path("userId").asText()).isEqualTo(LEARNER_2);
            assertThat(board.path("me").path("points").asLong()).isEqualTo(20);
            assertThat(board.path("me").path("rank").isNull()).isTrue();
            assertThat(board.path("me").path("teamName").isNull()).isTrue();
        } finally {
            jdbc.update("update learning_hub.users set team_id = (select team_id from learning_hub.teams"
                + " where code = 'BIDV110B8031280') where user_id = ?", LEARNER_2);
        }
    }

    @Test
    void aNonLearnerIsRefusedAndBadParametersAreRejected() {
        api.http(ADMIN, get(URL), 403, "CORE-ACCESS_DENIED");
        api.error(LEARNER, get(URL + "?scope=planet"), "CORE-VALIDATION_FAILED");
        api.error(LEARNER, get(URL + "?size=101"), "CORE-VALIDATION_FAILED");
        api.error(LEARNER, get(URL + "?size=0"), "CORE-VALIDATION_FAILED");
        api.error(LEARNER, get(URL + "?page=-1"), "CORE-VALIDATION_FAILED");
        // Giảng viên cũng là học viên (LEARNER, OWN) nên xem được bảng.
        assertThat(api.ok(INSTRUCTOR, get(URL)).path("me").path("userId").asText()).isEqualTo(INSTRUCTOR);
    }

    @Test
    void theBoardAndThePointsCardAgree() {
        days(LEARNER, 4);

        JsonNode board = api.ok(LEARNER, get(URL));
        JsonNode card = api.ok(LEARNER, get("/api/v1/me/learning/points"));

        assertThat(board.path("me").path("points").asLong()).isEqualTo(card.path("total").asLong()).isEqualTo(40);
        assertThat(board.path("me").path("streakDays").asInt()).isEqualTo(card.path("streakDays").asInt());
    }

    /** count ngày học, mỗi ngày 10 XP, ghi thẳng vào sổ XP. */
    private void days(String user, int count) {
        jdbc.update("""
            insert into learning_hub.gamification_config_versions (version_no, lesson_xp, quiz_pass_xp,
                first_try_bonus_xp, first_try_threshold_percent, community_xp, community_daily_cap)
            values (1, 5, 30, 20, 90, 10, 30) on conflict (version_no) do nothing
            """);
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                    config_version_id)
                select ?, 10, now(), 'LESSON', ?, version_id from learning_hub.gamification_config_versions
                where version_no = 1
                """, user, "lb-" + i);
        }
    }

    private static List<String> ids(JsonNode items) {
        List<String> result = new ArrayList<>();
        items.forEach(item -> result.add(item.path("userId").asText()));
        return result;
    }

    private static List<Integer> ranks(JsonNode items) {
        List<Integer> result = new ArrayList<>();
        items.forEach(item -> result.add(item.path("rank").asInt()));
        return result;
    }

    private static List<Long> points(JsonNode items) {
        List<Long> result = new ArrayList<>();
        items.forEach(item -> result.add(item.path("points").asLong()));
        return result;
    }

    private static List<String> names(JsonNode... rows) {
        return Arrays.stream(rows).map(row -> row.path("fullName").asText()).toList();
    }
}
