package vn.com.coreacademy.learning.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.learning.dto.GamificationConfig;

/**
 * Sổ ghi XP chỉ cộng thêm và các phiên bản cấu hình. Chỉ có thao tác ghi thêm: database chặn UPDATE và DELETE trên
 * sổ, và không có hàm sửa hay xóa ở đây.
 */
@Repository
@RequiredArgsConstructor
public class XpLedgerRepository {
    private final EntityManager entityManager;

    /** Phiên bản cấu hình lớn nhất, đang áp dụng; rỗng khi chưa có phiên bản nào. */
    public Optional<GamificationConfig> latestConfig() {
        List<?> rows = entityManager.createNativeQuery("""
            select version_id, version_no, lesson_xp, quiz_pass_xp, first_try_bonus_xp, first_try_threshold_percent,
                   community_xp, community_daily_cap
            from learning_hub.gamification_config_versions
            order by version_no desc
            limit 1
            """).getResultList();
        return rows.stream().map(Object[].class::cast).map(row -> new GamificationConfig(number(row[0]).longValue(),
            number(row[1]).intValue(), number(row[2]).intValue(), number(row[3]).intValue(),
            number(row[4]).intValue(), number(row[5]).intValue(), number(row[6]).intValue(),
            number(row[7]).intValue())).findFirst();
    }

    /** Tạo phiên bản 1; hai request đồng thời cùng gieo thì một bên bị bỏ qua nhờ khóa duy nhất của số phiên bản. */
    public void seedFirstConfig(int lessonXp, int quizPassXp, int firstTryBonusXp, int firstTryThresholdPercent,
                                int communityXp, int communityDailyCap) {
        entityManager.createNativeQuery("""
            insert into learning_hub.gamification_config_versions (version_no, lesson_xp, quiz_pass_xp,
                first_try_bonus_xp, first_try_threshold_percent, community_xp, community_daily_cap)
            values (1, :lesson, :quiz, :bonus, :threshold, :community, :cap)
            on conflict (version_no) do nothing
            """)
            .setParameter("lesson", lessonXp)
            .setParameter("quiz", quizPassXp)
            .setParameter("bonus", firstTryBonusXp)
            .setParameter("threshold", firstTryThresholdPercent)
            .setParameter("community", communityXp)
            .setParameter("cap", communityDailyCap)
            .executeUpdate();
    }

    /** Ghi một dòng; trả false khi (user, loại nguồn, mã nguồn) đã có dòng nên không ghi thêm. */
    public boolean append(String userId, int amount, Instant occurredAt, String sourceKind, String sourceRef,
                          long configVersionId, String reason) {
        return entityManager.createNativeQuery("""
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                config_version_id, reason)
            values (:user, :amount, :at, :kind, :ref, :version, :reason)
            on conflict (user_id, source_kind, source_ref) do nothing
            """)
            .setParameter("user", userId)
            .setParameter("amount", amount)
            .setParameter("at", occurredAt)
            .setParameter("kind", sourceKind)
            .setParameter("ref", sourceRef)
            .setParameter("version", configVersionId)
            .setParameter("reason", reason)
            .executeUpdate() > 0;
    }

    private static Number number(Object value) {
        return (Number) value;
    }
}
