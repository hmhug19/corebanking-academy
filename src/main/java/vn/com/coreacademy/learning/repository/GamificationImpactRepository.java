package vn.com.coreacademy.learning.repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Chỉ số tổng hợp cho báo cáo tác động. Mọi truy vấn nhận danh sách học viên trong phạm vi đã được server chốt và chỉ
 * trả số đếm hoặc tổng; không trả dòng theo người. SQL thuần. Danh sách rỗng cho kết quả rỗng mà không truy vấn.
 */
@Repository
@RequiredArgsConstructor
public class GamificationImpactRepository {
    private static final String ZONE = "Asia/Ho_Chi_Minh";

    private final EntityManager entityManager;

    public record Week(LocalDate weekStart, int learners) {
    }

    public record Source(String source, long xp, int learners) {
    }

    public record Held(long badgeId, String name, int awarded) {
    }

    public record Assignments(int assigned, int completed, int onTime) {
    }

    /** Học viên hoạt động theo tuần: có ít nhất một dòng sổ XP (không tính điều chỉnh) trong tuần và trong kỳ. */
    public List<Week> weeklyActive(Collection<String> ids, Instant from, Instant to) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Week> weeks = new ArrayList<>();
        for (Object row : entityManager.createNativeQuery("""
            select to_char(date_trunc('week', occurred_at at time zone '%s'), 'YYYY-MM-DD'), count(distinct user_id)
            from learning_hub.xp_ledger
            where user_id in (:ids) and source_kind <> 'ADJUSTMENT' and occurred_at >= :from and occurred_at < :to
            group by 1 order by 1
            """.formatted(ZONE)).setParameter("ids", ids).setParameter("from", from).setParameter("to", to)
            .getResultList()) {
            Object[] values = (Object[]) row;
            weeks.add(new Week(LocalDate.parse((String) values[0]), ((Number) values[1]).intValue()));
        }
        return weeks;
    }

    /** Số cặp (học viên, tuần) có hoạt động trong khoảng; chia cho số tuần là học viên hoạt động trung bình mỗi tuần. */
    public int activeLearnerWeeks(Collection<String> ids, Instant from, Instant to) {
        if (ids.isEmpty()) {
            return 0;
        }
        return ((Number) entityManager.createNativeQuery("""
            select count(*) from (
                select distinct user_id, date_trunc('week', occurred_at at time zone '%s')
                from learning_hub.xp_ledger
                where user_id in (:ids) and source_kind <> 'ADJUSTMENT' and occurred_at >= :from and occurred_at < :to
            ) x
            """.formatted(ZONE)).setParameter("ids", ids).setParameter("from", from).setParameter("to", to)
            .getSingleResult()).intValue();
    }

    /**
     * Khóa được giao (chưa thu hồi) có hạn bắt đầu trong khoảng: số giao, số lần học đã hoàn thành và số hoàn thành
     * đúng hạn (không có hạn thì tính đúng hạn).
     */
    public Assignments assignments(Collection<String> ids, Instant from, Instant to) {
        if (ids.isEmpty()) {
            return new Assignments(0, 0, 0);
        }
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            select count(*),
                   count(*) filter (where e.status = 'completed'),
                   count(*) filter (where e.status = 'completed'
                                      and (r.effective_due_at is null or e.completed_at <= r.effective_due_at))
            from learning_hub.assignment_recipients r
            join learning_hub.course_enrollments e on e.enrollment_id = r.enrollment_id
            where r.user_id in (:ids) and r.status <> 'revoked'
              and r.effective_start_at >= :from and r.effective_start_at < :to
            """).setParameter("ids", ids).setParameter("from", from).setParameter("to", to).getSingleResult();
        return new Assignments(((Number) row[0]).intValue(), ((Number) row[1]).intValue(),
            ((Number) row[2]).intValue());
    }

    public List<Source> xpBySource(Collection<String> ids, Instant from, Instant to) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Source> sources = new ArrayList<>();
        for (Object row : entityManager.createNativeQuery("""
            select source_kind, sum(amount), count(distinct user_id)
            from learning_hub.xp_ledger
            where user_id in (:ids) and occurred_at >= :from and occurred_at < :to
            group by source_kind order by source_kind
            """).setParameter("ids", ids).setParameter("from", from).setParameter("to", to).getResultList()) {
            Object[] values = (Object[]) row;
            sources.add(new Source((String) values[0], ((Number) values[1]).longValue(),
                ((Number) values[2]).intValue()));
        }
        return sources;
    }

    /** Huy hiệu còn dùng (kể cả 0 lượt) và huy hiệu đã ngừng dùng có lượt trao trong kỳ. */
    public List<Held> badgesAwarded(Collection<String> ids, Instant from, Instant to) {
        List<Held> held = new ArrayList<>();
        Collection<String> users = ids.isEmpty() ? List.of("__no_user__") : ids;
        for (Object row : entityManager.createNativeQuery("""
            select b.badge_id, b.name, count(u.user_id)
            from learning_hub.badge_definitions b
            left join learning_hub.user_badges u on u.badge_id = b.badge_id and u.user_id in (:ids)
                 and u.earned_at >= :from and u.earned_at < :to
            group by b.badge_id, b.name, b.status, b.sort_no
            having b.status = 'active' or count(u.user_id) > 0
            order by b.sort_no, b.badge_id
            """).setParameter("ids", users).setParameter("from", from).setParameter("to", to).getResultList()) {
            Object[] values = (Object[]) row;
            held.add(new Held(((Number) values[0]).longValue(), (String) values[1], ((Number) values[2]).intValue()));
        }
        return held;
    }

    /** Số học viên theo cấp đã lưu; học viên chưa có dòng được bên gọi tính vào cấp 1. */
    public Map<Integer, Integer> learnersPerLevel(Collection<String> ids) {
        Map<Integer, Integer> levels = new HashMap<>();
        if (ids.isEmpty()) {
            return levels;
        }
        for (Object row : entityManager.createNativeQuery("""
            select level_no, count(*) from learning_hub.learner_levels where user_id in (:ids) group by level_no
            """).setParameter("ids", ids).getResultList()) {
            Object[] values = (Object[]) row;
            levels.put(((Number) values[0]).intValue(), ((Number) values[1]).intValue());
        }
        return levels;
    }
}
