package vn.com.coreacademy.learning;

import java.util.ArrayList;
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

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/**
 * Ẩn tên khỏi bảng xếp hạng: người khác không còn thấy, hạng khép lại, chính họ vẫn thấy hạng mình sẽ có nếu hiện. Ba học
 * viên của đơn vị seed có XP 30, 20 và 10.
 */
class LeaderboardOptOutIT extends AssessmentIntegrationTest {
    private static final String URL = "/api/v1/me/learning/leaderboard";
    private static final String PREFERENCES = "/api/v1/me/learning/preferences";
    private static final String THIRD = "000005";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private int sequence;

    @BeforeEach
    void threeLearnersWithXp() {
        api = new ApiClient(mvc, json);
        jdbc.execute("truncate learning_hub.xp_ledger");
        jdbc.update("delete from learning_hub.learner_preferences");
        jdbc.update("""
            insert into learning_hub.gamification_config_versions (version_no, lesson_xp, quiz_pass_xp,
                first_try_bonus_xp, first_try_threshold_percent, community_xp, community_daily_cap)
            values (1, 5, 30, 20, 90, 10, 30) on conflict (version_no) do nothing
            """);
        xp(LEARNER, 30);
        xp(LEARNER_2, 20);
        xp(THIRD, 10);
    }

    @AfterEach
    void clean() {
        jdbc.update("delete from learning_hub.learner_preferences");
    }

    private void xp(String user, int amount) {
        jdbc.update("""
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                config_version_id)
            select ?, ?, now(), 'LESSON', ?, version_id
            from learning_hub.gamification_config_versions where version_no = 1
            """, user, amount, "oo-" + (++sequence));
    }

    private void show(String user, boolean visible) {
        api.ok(user, api.put(Map.of("showOnLeaderboard", visible), PREFERENCES));
    }

    private JsonNode board(String user) {
        return api.ok(user, get(URL));
    }

    private static List<String> ids(JsonNode rows) {
        List<String> result = new ArrayList<>();
        rows.forEach(row -> result.add(row.path("userId").asText()));
        return result;
    }

    private static List<Integer> ranks(JsonNode rows) {
        List<Integer> result = new ArrayList<>();
        rows.forEach(row -> result.add(row.path("rank").asInt()));
        return result;
    }

    @Test
    void everyoneIsVisibleByDefault() {
        assertThat(api.ok(LEARNER, get(PREFERENCES)).path("showOnLeaderboard").asBoolean()).isTrue();
        assertThat(ids(board(LEARNER).path("top"))).containsExactly(LEARNER, LEARNER_2, THIRD);
    }

    @Test
    void aHiddenLearnerIsLeftOutOfOtherBoardsAndTheRanksCloseUpWithNoPlaceholder() {
        show(LEARNER_2, false);

        JsonNode board = board(LEARNER);

        assertThat(ids(board.path("top"))).containsExactly(LEARNER, THIRD);
        assertThat(ranks(board.path("top"))).as("closed up: 1, 2 rather than 1, 3").containsExactly(1, 2);
        assertThat(board.toString()).as("no row stands in for the hidden learner").doesNotContain("Ẩn danh");
        assertThat(ids(board(THIRD).path("top"))).doesNotContain(LEARNER_2);
    }

    @Test
    void theHiddenLearnerStillSeesTheirOwnRankAsIfVisibleAmongTheOthers() {
        show(LEARNER, false);

        JsonNode own = board(LEARNER);
        JsonNode other = board(LEARNER_2);

        assertThat(own.path("me").path("rank").asInt()).as("first, as if visible").isEqualTo(1);
        assertThat(own.path("me").path("points").asLong()).isEqualTo(30);
        assertThat(ids(own.path("top"))).containsExactly(LEARNER, LEARNER_2, THIRD);
        assertThat(own.path("top").get(0).path("me").asBoolean()).isTrue();
        assertThat(ids(other.path("top"))).as("others do not see them").containsExactly(LEARNER_2, THIRD);
        assertThat(ranks(other.path("top"))).containsExactly(1, 2);
        assertThat(other.path("me").path("rank").asInt()).isEqualTo(1);
    }

    @Test
    void theChoiceIsKeptCanBeUndoneAndIsOnlyTheCallers() {
        show(LEARNER, false);

        assertThat(api.ok(LEARNER, get(PREFERENCES)).path("showOnLeaderboard").asBoolean()).isFalse();
        assertThat(api.ok(LEARNER_2, get(PREFERENCES)).path("showOnLeaderboard").asBoolean())
            .as("someone else's preference is untouched").isTrue();

        show(LEARNER, true);

        assertThat(api.ok(LEARNER, get(PREFERENCES)).path("showOnLeaderboard").asBoolean()).isTrue();
        assertThat(ids(board(LEARNER_2).path("top"))).containsExactly(LEARNER, LEARNER_2, THIRD);
    }

    @Test
    void hidingTwiceChangesNothingFurther() {
        show(LEARNER, false);
        show(LEARNER, false);

        assertThat(jdbc.queryForObject("select count(*) from learning_hub.learner_preferences", Long.class))
            .isEqualTo(1L);
    }

    @Test
    void aNonLearnerIsRefusedAndAMissingValueIsRejected() {
        api.http(ADMIN, get(PREFERENCES), 403, "CORE-ACCESS_DENIED");
        api.http(ADMIN, api.put(Map.of("showOnLeaderboard", true), PREFERENCES), 403, "CORE-ACCESS_DENIED");
        api.error(LEARNER, api.put(Map.of(), PREFERENCES), "CORE-VALIDATION_FAILED");
    }
}
