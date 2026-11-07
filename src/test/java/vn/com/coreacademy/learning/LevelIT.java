package vn.com.coreacademy.learning;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.learning.dto.LearningLevelViews.LevelStep;
import vn.com.coreacademy.learning.dto.LearningLevelViews.MyLevel;
import vn.com.coreacademy.learning.service.LevelService;
import vn.com.coreacademy.learning.service.XpLedgerService;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;

/** Cấp độ: bậc thang mặc định, tiến độ tới cấp kế tiếp, cấp cao nhất và việc không bao giờ bị hạ cấp. */
class LevelIT extends AssessmentIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LevelService levels;

    @Autowired
    private XpLedgerService xp;

    private ApiClient api;

    @BeforeEach
    void client() {
        api = new ApiClient(mvc, json);
        xp.currentConfig();
        levels.currentLadder();
    }

    @Test
    void theDefaultLadderHasSixLevelsInConfigVersionOne() {
        List<LevelStep> ladder = levels.currentLadder();

        assertThat(ladder).extracting(LevelStep::level).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(ladder).extracting(LevelStep::name).containsExactly("Giao dịch viên tập sự", "Giao dịch viên",
            "Chuyên viên", "Chuyên viên chính", "Chuyên gia nghiệp vụ", "Chuyên gia Core Banking");
        assertThat(ladder).extracting(LevelStep::minXp).containsExactly(0, 200, 600, 1500, 3500, 7000);
    }

    @Test
    void aLevelComesFromTotalXpWithTheXpAndProgressToTheNext() {
        assertThat(levels.refresh("LV-ZERO")).isEqualTo(new MyLevel(1, "Giao dịch viên tập sự", 0, 200L, 0));

        xp("LV-HALF", 100);
        assertThat(levels.refresh("LV-HALF")).isEqualTo(new MyLevel(1, "Giao dịch viên tập sự", 100, 100L, 50));

        xp("LV-TWO", 200);
        assertThat(levels.refresh("LV-TWO")).isEqualTo(new MyLevel(2, "Giao dịch viên", 200, 400L, 0));

        xp("LV-THREE", 1200);
        assertThat(levels.refresh("LV-THREE")).isEqualTo(new MyLevel(3, "Chuyên viên", 1200, 300L, 66));
    }

    @Test
    void atTheTopLevelThereIsNoNextLevelAndNoProgress() {
        xp("LV-TOP", 7000);

        MyLevel top = levels.refresh("LV-TOP");

        assertThat(top.level()).isEqualTo(6);
        assertThat(top.name()).isEqualTo("Chuyên gia Core Banking");
        assertThat(top.xpToNext()).isNull();
        assertThat(top.progressPercent()).isNull();
    }

    @Test
    void raisingTheThresholdsNeverLowersAnyonesLevel() {
        xp("LV-KEEP", 650);
        assertThat(levels.refresh("LV-KEEP").level()).isEqualTo(3);
        xp("LV-RISE", 150);

        long raised = addVersion(List.of(new LevelStep(1, "Cấp 1", 0), new LevelStep(2, "Cấp 2", 1000),
            new LevelStep(3, "Cấp 3", 3000)));
        try {
            levels.refreshAll();

            MyLevel kept = levels.refresh("LV-KEEP");
            assertThat(kept.level()).as("still level 3 though 650 XP is below the new level 2").isEqualTo(3);
            assertThat(kept.name()).isEqualTo("Cấp 3");
            assertThat(kept.xpToNext()).as("no level above the new ladder's top").isNull();
            assertThat(levels.refresh("LV-RISE").level()).isEqualTo(1);
        } finally {
            addVersion(LevelService.DEFAULT_LADDER);
        }
        assertThat(raised).isPositive();
    }

    @Test
    void theEndpointReturnsTheCallersLevelAndRefusesANonLearner() {
        JsonNode mine = api.ok(LEARNER, get("/api/v1/me/learning/level"));

        assertThat(mine.fieldNames()).toIterable().containsExactly("level", "name", "xp", "xpToNext",
            "progressPercent");
        assertThat(mine.path("level").asInt()).isBetween(1, 6);
        assertThat(mine.path("name").asText()).isNotBlank();
        api.http(ADMIN, get("/api/v1/me/learning/level"), 403, "CORE-ACCESS_DENIED");
    }

    private void xp(String user, int amount) {
        jdbc.update("""
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                config_version_id)
            select ?, ?, now(), 'ADJUSTMENT', 'lv-' || gen_random_uuid(), max(version_id)
            from learning_hub.gamification_config_versions
            """, user, amount);
    }

    /** Thêm một phiên bản cấu hình mới giữ trọng số cũ và dùng bậc thang đã cho. */
    private long addVersion(List<LevelStep> ladder) {
        long version = jdbc.queryForObject("""
            insert into learning_hub.gamification_config_versions (version_no, lesson_xp, quiz_pass_xp,
                first_try_bonus_xp, first_try_threshold_percent, community_xp, community_daily_cap)
            select max(version_no) + 1, lesson_xp, quiz_pass_xp, first_try_bonus_xp, first_try_threshold_percent,
                   community_xp, community_daily_cap
            from learning_hub.gamification_config_versions
            where version_no = (select max(version_no) from learning_hub.gamification_config_versions)
            group by lesson_xp, quiz_pass_xp, first_try_bonus_xp, first_try_threshold_percent, community_xp,
                     community_daily_cap
            returning version_id
            """, Long.class);
        for (LevelStep step : ladder) {
            jdbc.update("insert into learning_hub.gamification_levels (version_id, level_no, name, min_xp)"
                + " values (?, ?, ?, ?)", version, step.level(), step.name(), step.minXp());
        }
        return version;
    }
}
