package vn.com.coreacademy.learning;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.learning.service.XpLedgerService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XP cộng đồng: đóng góp được chấp nhận hoặc hữu ích, trần mỗi ngày Asia/Ho_Chi_Minh, và đối soát lịch sử. Người và
 * nội dung là mã giả (bảng không có FK) để mỗi ca có dữ liệu riêng; sự kiện đóng góp dựng thẳng vào bảng của cộng đồng.
 */
class XpCommunityIT extends AssessmentIntegrationTest {
    private static final Instant NOON = Instant.parse("2030-03-01T05:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private XpLedgerService xp;

    @Test
    void anAcceptedAnswerAndAHelpfulMarkEachWriteOneRowAndOnlyOnce() {
        long content = 910_001;
        credit("XC-ONE", content, "accepted_answer", 5, "XC-ASKER", NOON);
        credit("XC-ONE", content, "received_upvote", 1, "XC-VOTER", NOON.plusSeconds(60));

        assertThat(xp.communityContributions(content)).isEqualTo(2);
        assertThat(xp.communityContributions(content)).as("a rerun writes nothing").isZero();

        assertThat(rows("XC-ONE")).containsExactlyInAnyOrder(content + ":accepted_answer:XC-ASKER:10",
            content + ":received_upvote:XC-VOTER:10");
    }

    @Test
    void maintenanceReviewsGiveNoXp() {
        long content = 910_002;
        credit("XC-MAINT", content, "verified_maintenance", 3, "XC-MAINT", NOON);

        assertThat(xp.communityContributions(content)).isZero();
        assertThat(rows("XC-MAINT")).isEmpty();
    }

    @Test
    void aDayIsCappedAndTheCapResetsAtMidnightInVietnam() {
        long content = 910_003;
        // 23:58 giờ VN ngày 1/3 (16:58Z): ba lần đủ trần 30, lần thứ tư và thứ năm không ghi.
        Instant lastMinute = Instant.parse("2030-03-01T16:58:00Z");
        for (int i = 1; i <= 5; i++) {
            credit("XC-CAP", content, "received_upvote", 1, "XC-V" + i, lastMinute.plusSeconds(i));
        }
        // 00:01 giờ VN ngày 2/3 (17:01Z): ngày mới, trần mới.
        credit("XC-CAP", content, "received_upvote", 1, "XC-V6", Instant.parse("2030-03-01T17:01:00Z"));

        xp.communityContributions(content);

        assertThat(rows("XC-CAP")).hasSize(4);
        assertThat(total("XC-CAP")).isEqualTo(40);
        assertThat(jdbc.queryForObject("""
            select sum(amount) from learning_hub.xp_ledger where user_id = 'XC-CAP' and source_kind = 'COMMUNITY'
              and (occurred_at at time zone 'Asia/Ho_Chi_Minh')::date = date '2030-03-01'
            """, Integer.class)).isEqualTo(30);
    }

    @Test
    void acceptingAgainAfterAReversalDoesNotPayTwice() {
        long content = 910_004;
        long first = credit("XC-AGAIN", content, "accepted_answer", 5, "XC-ASKER", NOON);
        xp.communityContributions(content);

        reverse(first, content, "XC-ASKER");
        credit("XC-AGAIN", content, "accepted_answer", 5, "XC-ASKER", NOON.plusSeconds(3600));
        xp.communityContributions(content);

        assertThat(total("XC-AGAIN")).as("XP stays, and is not paid twice").isEqualTo(10);
    }

    @Test
    void aContributionReversedBeforeItWasPaidEarnsNothing() {
        long content = 910_005;
        long first = credit("XC-UNDO", content, "accepted_answer", 5, "XC-ASKER", NOON);
        reverse(first, content, "XC-ASKER");

        assertThat(xp.communityContributions(content)).isZero();
    }

    @Test
    void theBackfillReplaysPastContributionsWithTheCapAndIsSafeToRerun() {
        long content = 910_006;
        for (int i = 1; i <= 4; i++) {
            credit("XC-OLD", content, "received_upvote", 1, "XC-V" + i, NOON.plusSeconds(i));
        }
        // Một dòng đã được ghi lúc chạy thật: script không ghi lại và vẫn tính nó vào trần ngày.
        jdbc.update("""
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                config_version_id)
            select 'XC-OLD', 10, ?, 'COMMUNITY', ?, version_id from learning_hub.gamification_config_versions
            where version_no = 1
            """, Timestamp.from(NOON.plusSeconds(1)), content + ":received_upvote:XC-V1");

        runBackfill();
        runBackfill();

        assertThat(rows("XC-OLD")).hasSize(3);
        assertThat(total("XC-OLD")).as("capped at 30 for the day").isEqualTo(30);
    }

    private void runBackfill() {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(
                "docs/sql/upgrade/2026-11-07-xp-community-backfill.sql"));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long credit(String user, long content, String reason, int points, String actor, Instant at) {
        xp.currentConfig();
        long event = outbox(actor, content);
        return jdbc.queryForObject("""
            insert into learning_hub.community_contribution_events (org_id, user_id, content_id, source_event_id,
                reason_code, points_delta, policy_version, occurred_at, created_by, updated_by)
            values (1, ?, ?, ?, ?, ?, 1, ?, 'XC', 'XC') returning contribution_event_id
            """, Long.class, user, content, event, reason, points, Timestamp.from(at));
    }

    private void reverse(long positiveId, long content, String actor) {
        long event = outbox(actor, content);
        jdbc.update("""
            insert into learning_hub.community_contribution_events (org_id, user_id, content_id, source_event_id,
                reason_code, points_delta, policy_version, reverses_event_id, occurred_at, created_by, updated_by)
            select org_id, user_id, content_id, ?, reason_code, -points_delta, policy_version, contribution_event_id,
                now(), 'XC', 'XC' from learning_hub.community_contribution_events where contribution_event_id = ?
            """, event, positiveId);
    }

    private long outbox(String actor, long content) {
        return jdbc.queryForObject("""
            insert into learning_hub.community_outbox_events (owner_org_id, event_type, actor_user_id, content_id,
                dedup_key, event_status, available_at, attempt_count, created_by, updated_by)
            values (1, 'answer_accept_changed', ?, ?, ?, 'processed', now(), 0, 'XC', 'XC') returning event_id
            """, Long.class, actor, content, UUID.randomUUID().toString());
    }

    /** Mã nguồn và số XP của các dòng cộng đồng của một người, dạng "ref:amount". */
    private List<String> rows(String user) {
        return jdbc.queryForList("""
            select source_ref || ':' || amount from learning_hub.xp_ledger
            where user_id = ? and source_kind = 'COMMUNITY' order by source_ref
            """, String.class, user);
    }

    private int total(String user) {
        return jdbc.queryForObject("select coalesce(sum(amount), 0) from learning_hub.xp_ledger where user_id = ?",
            Integer.class, user);
    }
}
