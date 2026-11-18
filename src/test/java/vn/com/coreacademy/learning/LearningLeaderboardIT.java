package vn.com.coreacademy.learning;

import java.sql.Timestamp;
import java.text.Collator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

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
        jdbc.update("delete from learning_hub.user_roles where user_id like '9100%'");
        jdbc.update("delete from learning_hub.users where user_id like '9100%'");
        jdbc.update("delete from learning_hub.user_roles where user_id = ?", OUTSIDER);
        jdbc.update("delete from learning_hub.users where user_id = ?", OUTSIDER);
        jdbc.update("delete from learning_hub.orgs where code = 'LB-OTHER'");
    }

    @Test
    void orgIsTheDefaultAndRanksTiesTogetherWithZeroPointUsersLast() {
        days(LEARNER, 3);
        days(LEARNER_2, 3);

        JsonNode board = api.ok(LEARNER, get(URL));
        JsonNode items = board.path("top");

        assertThat(board.has("page")).as("no paging").isFalse();
        assertThat(board.has("totalElements")).as("no total count").isFalse();
        assertThat(board.path("period").asText()).as("month is the default period").isEqualTo("month");
        assertThat(items).as("three learners of the unit").hasSize(3);
        assertThat(ids(items)).containsExactlyInAnyOrder(LEARNER, LEARNER_2, "000005");
        assertThat(ranks(items)).as("1, 1, 3").containsExactly(1, 1, 3);
        assertThat(points(items)).containsExactly(30L, 30L, 0L);
        assertThat(items.get(2).path("userId").asText()).as("the zero-point user is last").isEqualTo("000005");
        assertThat(items.get(0).path("streakDays").asInt()).as("no lesson done, so no working-day streak").isZero();
        assertThat(items.get(0).path("teamName").asText()).isNotBlank();
        assertThat(names(items.get(0), items.get(1))).as("ties are ordered by name")
            .isSortedAccordingTo(Collator.getInstance(Locale.forLanguageTag("vi")));
    }

    @Test
    void scopesLimitWhoIsRanked() {
        days(LEARNER, 2);

        assertThat(ids(api.ok(LEARNER, get(URL + "?scope=team")).path("top")))
            .as("the caller's own team only").containsExactly(LEARNER);
        assertThat(ids(api.ok(LEARNER, get(URL + "?scope=org")).path("top")))
            .containsExactlyInAnyOrder(LEARNER, LEARNER_2, "000005");
        assertThat(ids(api.ok(LEARNER, get(URL + "?scope=all")).path("top")))
            .as("every learner of the system").containsExactlyInAnyOrder(LEARNER, LEARNER_2, "000005", OUTSIDER);
    }

    @Test
    void notEveryoneActiveAndEligibleIsRanked() {
        jdbc.update("update learning_hub.users set status = 'inactive' where user_id = '000005'");
        try {
            assertThat(ids(api.ok(LEARNER, get(URL)).path("top")))
                .as("an inactive learner is not ranked").containsExactlyInAnyOrder(LEARNER, LEARNER_2);
        } finally {
            jdbc.update("update learning_hub.users set status = 'active' where user_id = '000005'");
        }
        // Quản trị viên không có vai trò học viên nên không có trong bảng dù cùng đơn vị.
        assertThat(ids(api.ok(LEARNER, get(URL)).path("top"))).doesNotContain("000001", "000002",
            "000003");
    }

    @Test
    void insideTheTopTenMeIsHighlightedInPlaceWithNothingAbove() {
        days(LEARNER, 3);

        JsonNode board = api.ok(LEARNER, get(URL));

        assertThat(board.path("top").get(0).path("userId").asText()).isEqualTo(LEARNER);
        assertThat(board.path("top").get(0).path("me").asBoolean()).isTrue();
        assertThat(board.path("me").path("rank").asInt()).isEqualTo(1);
        assertThat(board.path("me").path("points").asLong()).isEqualTo(30);
        assertThat(board.path("above")).isEmpty();
        assertThat(board.path("gapToAbove").isNull()).isTrue();
    }

    @Test
    void outsideTheTopTenOnlyTenRowsAreReturnedWithTheTwoAboveAndTheGap() {
        days(LEARNER, 3);
        extraLearners(12, 100, 1);

        JsonNode board = api.ok(LEARNER, get(URL + "?scope=all"));

        assertThat(board.path("top")).as("only the top ten").hasSize(10);
        assertThat(ranks(board.path("top"))).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(points(board.path("top")).get(0)).isEqualTo(111L);
        assertThat(board.path("top").findValuesAsText("me")).as("the caller is not in the top").doesNotContain("true");
        assertThat(board.path("me").path("userId").asText()).isEqualTo(LEARNER);
        assertThat(board.path("me").path("rank").asInt()).isEqualTo(13);
        assertThat(ranks(board.path("above"))).as("the two just above, nearest last").containsExactly(11, 12);
        assertThat(points(board.path("above"))).containsExactly(101L, 100L);
        assertThat(board.path("gapToAbove").asLong()).as("XP to pass the person above").isEqualTo(70);
    }

    @Test
    void aTopTenOfTiedMembersStillReturnsTenRows() {
        extraLearners(11, 50, 0);

        JsonNode board = api.ok(LEARNER, get(URL + "?scope=all"));

        assertThat(board.path("top")).hasSize(10);
        assertThat(ranks(board.path("top"))).as("all tied").containsOnly(1);
        assertThat(board.path("me").path("points").asLong()).isZero();
    }

    @Test
    void thePeriodSumsLedgerRowsByTheirVietnamDayAndOffsetStepsBack() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate monthStart = today.withDayOfMonth(1);
        xpAt(LEARNER, 7, monthStart.atTime(0, 30), "m-0");
        xpAt(LEARNER, 11, monthStart.minusDays(1).atTime(23, 30), "m-1");
        xpAt(LEARNER, 13, monthStart.minusMonths(2).atTime(12, 0), "m-2");

        assertThat(myPoints("period=month")).as("00:30 on the 1st (VN) is this month, not last").isEqualTo(7);
        assertThat(myPoints("period=month&offset=1")).as("23:30 on the last day (VN) is last month").isEqualTo(11);
        assertThat(myPoints("period=month&offset=2")).isEqualTo(13);
        assertThat(myPoints("period=all")).isEqualTo(31);
        assertThat(myPoints("period=all&offset=3")).as("offset is ignored for all").isEqualTo(31);
        JsonNode board = api.ok(LEARNER, get(URL + "?period=month&offset=1"));
        assertThat(board.path("from").asText()).isEqualTo(monthStart.minusMonths(1).toString());
        assertThat(board.path("to").asText()).isEqualTo(monthStart.minusDays(1).toString());
        assertThat(board.path("offset").asInt()).isEqualTo(1);
        assertThat(api.ok(LEARNER, get(URL + "?period=all")).path("from").isNull()).isTrue();
    }

    @Test
    void aWeekRunsMondayToSundayInVietnamTime() {
        LocalDate monday = LocalDate.now(ZONE).with(DayOfWeek.MONDAY);
        xpAt(LEARNER, 5, monday.atTime(0, 30), "w-0");
        xpAt(LEARNER, 9, monday.minusDays(1).atTime(23, 30), "w-1");

        assertThat(myPoints("period=week")).isEqualTo(5);
        assertThat(myPoints("period=week&offset=1")).isEqualTo(9);
        JsonNode board = api.ok(LEARNER, get(URL + "?period=week&offset=1"));
        assertThat(board.path("from").asText()).isEqualTo(monday.minusWeeks(1).toString());
        assertThat(board.path("to").asText()).isEqualTo(monday.minusDays(1).toString());
        assertThat(api.ok(LEARNER, get(URL + "?period=week")).path("top").get(0).path("userId").asText())
            .isEqualTo(LEARNER);
    }

    @Test
    void ranksWithinAPeriodIgnoreXpFromOtherPeriods() {
        LocalDate monthStart = LocalDate.now(ZONE).withDayOfMonth(1);
        xpAt(LEARNER, 100, monthStart.minusMonths(1).atTime(12, 0), "o-1");
        xpAt(LEARNER_2, 10, monthStart.atTime(12, 0), "o-2");

        JsonNode thisMonth = api.ok(LEARNER, get(URL + "?period=month"));
        assertThat(ids(thisMonth.path("top")).get(0)).isEqualTo(LEARNER_2);
        assertThat(thisMonth.path("me").path("rank").asInt()).isEqualTo(2);
        assertThat(ids(api.ok(LEARNER, get(URL + "?period=all")).path("top")).get(0)).isEqualTo(LEARNER);
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

            assertThat(board.path("top")).isEmpty();
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
    void signedInNonLearnersCanReadAndBadParametersAreRejected() {
        assertThat(api.ok(ADMIN, get(URL)).path("top").isArray()).isTrue();
        api.http(null, get(URL), 401, "CORE-UNAUTHENTICATED");
        api.error(LEARNER, get(URL + "?scope=planet"), "CORE-VALIDATION_FAILED");
        api.error(LEARNER, get(URL + "?period=year"), "CORE-VALIDATION_FAILED");
        api.error(LEARNER, get(URL + "?offset=-1"), "CORE-VALIDATION_FAILED");
        api.error(LEARNER, get(URL + "?offset=521"), "CORE-VALIDATION_FAILED");
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

    /** count học viên của đơn vị khác, XP lần lượt first, first+step, ... trong tháng này. */
    private void extraLearners(int count, int first, int step) {
        days(LEARNER, 0);
        for (int i = 0; i < count; i++) {
            String id = "9100" + (10 + i);
            jdbc.update("""
                insert into learning_hub.users (user_id, user_name, full_name, org_id, auth_provider, password_hash)
                select ?, ?, ?, org_id, 'LOCAL', 'x' from learning_hub.orgs where code = 'LB-OTHER'
                """, id, "lb.x" + i, "Học viên thêm " + (char) ('A' + i));
            jdbc.update("""
                insert into learning_hub.user_roles (user_id, role_id, scope)
                select ?, role_id, 'OWN' from learning_hub.roles where code = 'LEARNER'
                """, id);
            jdbc.update("""
                insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                    config_version_id)
                select ?, ?, now(), 'LESSON', ?, version_id from learning_hub.gamification_config_versions
                where version_no = 1
                """, id, first + i * step, "lb-x-" + i);
        }
    }

    /** Một dòng sổ XP tại giờ Asia/Ho_Chi_Minh đã cho. */
    private void xpAt(String user, int amount, LocalDateTime vietnamTime, String ref) {
        days(user, 0);
        jdbc.update("""
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                config_version_id)
            select ?, ?, ?, 'ADJUSTMENT', ?, version_id from learning_hub.gamification_config_versions
            where version_no = 1
            """, user, amount, Timestamp.from(vietnamTime.atZone(ZONE).toInstant()), "lb-at-" + ref);
    }

    private long myPoints(String query) {
        return api.ok(LEARNER, get(URL + "?" + query)).path("me").path("points").asLong();
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
