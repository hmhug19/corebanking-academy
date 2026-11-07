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

    /** Ghi dòng điều chỉnh (số có dấu) với lý do; trả entry_id. Mã nguồn là UUID mới nên không bao giờ trùng. */
    public long appendAdjustment(String userId, int amount, Instant occurredAt, String sourceRef,
                                 long configVersionId, String reason) {
        return ((Number) entityManager.createNativeQuery("""
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                config_version_id, reason)
            values (:user, :amount, :at, 'ADJUSTMENT', :ref, :version, :reason)
            returning entry_id
            """)
            .setParameter("user", userId)
            .setParameter("amount", amount)
            .setParameter("at", occurredAt)
            .setParameter("ref", sourceRef)
            .setParameter("version", configVersionId)
            .setParameter("reason", reason)
            .getSingleResult()).longValue();
    }

    /**
     * Ghi XP cộng đồng cho các đóng góp được chấp nhận hoặc hữu ích (accepted_answer, received_upvote) chưa có dòng.
     * Mỗi đóng góp một dòng, mã nguồn là nội dung + lý do + người thực hiện nên chấp nhận lại sau khi bỏ chấp nhận
     * không ghi thêm. Mỗi người tối đa cap XP mỗi ngày Asia/Ho_Chi_Minh, tính theo thời điểm đóng góp; phần vượt
     * trần không ghi dòng nào. contentId null là xét mọi nội dung. Trả số dòng đã ghi.
     */
    public int awardCommunity(Long contentId, int xp, int cap, long configVersionId) {
        return entityManager.createNativeQuery("""
            with pending as (
              select distinct on (ref) user_id, occurred_at, ref from (
                select e.user_id, e.occurred_at, e.contribution_event_id,
                       e.content_id::text || ':' || e.reason_code || ':' || o.actor_user_id as ref
                from learning_hub.community_contribution_events e
                join learning_hub.community_outbox_events o on o.event_id = e.source_event_id
                where e.points_delta > 0 and e.reason_code in ('accepted_answer', 'received_upvote')
                  and o.actor_user_id is not null
                  and (cast(:content as bigint) is null or e.content_id = cast(:content as bigint))
                  and not exists (select 1 from learning_hub.community_contribution_events r
                                  where r.reverses_event_id = e.contribution_event_id)
              ) x
              order by ref, occurred_at, contribution_event_id
            ), ranked as (
              select p.user_id, p.occurred_at, p.ref,
                     (p.occurred_at at time zone 'Asia/Ho_Chi_Minh')::date as day,
                     row_number() over (partition by p.user_id, (p.occurred_at at time zone 'Asia/Ho_Chi_Minh')::date
                                        order by p.occurred_at, p.ref) as rn
              from pending p
              where not exists (select 1 from learning_hub.xp_ledger l where l.user_id = p.user_id
                                and l.source_kind = 'COMMUNITY' and l.source_ref = p.ref)
            )
            insert into learning_hub.xp_ledger (user_id, amount, occurred_at, source_kind, source_ref,
                config_version_id)
            select r.user_id, least(:xp, :cap - coalesce(prior.total, 0) - :xp * (r.rn - 1)), r.occurred_at,
                   'COMMUNITY', r.ref, :version
            from ranked r
            left join lateral (
              select sum(l.amount) as total from learning_hub.xp_ledger l
              where l.user_id = r.user_id and l.source_kind = 'COMMUNITY'
                and (l.occurred_at at time zone 'Asia/Ho_Chi_Minh')::date = r.day
            ) prior on true
            where least(:xp, :cap - coalesce(prior.total, 0) - :xp * (r.rn - 1)) > 0
            on conflict (user_id, source_kind, source_ref) do nothing
            """)
            .setParameter("content", contentId)
            .setParameter("xp", xp)
            .setParameter("cap", cap)
            .setParameter("version", configVersionId)
            .executeUpdate();
    }

    private static Number number(Object value) {
        return (Number) value;
    }
}
