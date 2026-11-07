package vn.com.coreacademy.learning.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.learning.dto.LearningLevelViews.LevelStep;

/** Bậc thang cấp độ của từng phiên bản cấu hình và cấp cao nhất mỗi học viên từng đạt (chỉ tăng). */
@Repository
@RequiredArgsConstructor
public class LevelRepository {
    private final EntityManager entityManager;

    /** Bậc thang của một phiên bản, cấp thấp trước; rỗng khi phiên bản chưa có bậc thang. */
    public List<LevelStep> ladder(long versionId) {
        List<?> rows = entityManager.createNativeQuery("""
            select level_no, name, min_xp from learning_hub.gamification_levels
            where version_id = :version order by level_no
            """).setParameter("version", versionId).getResultList();
        return rows.stream().map(Object[].class::cast)
            .map(row -> new LevelStep(((Number) row[0]).intValue(), (String) row[1], ((Number) row[2]).intValue()))
            .toList();
    }

    /** Ghi bậc thang cho phiên bản; bỏ qua cấp đã có. */
    public void seedLadder(long versionId, List<LevelStep> steps) {
        for (LevelStep step : steps) {
            entityManager.createNativeQuery("""
                insert into learning_hub.gamification_levels (version_id, level_no, name, min_xp)
                values (:version, :level, :name, :minXp)
                on conflict (version_id, level_no) do nothing
                """)
                .setParameter("version", versionId)
                .setParameter("level", step.level())
                .setParameter("name", step.name())
                .setParameter("minXp", step.minXp())
                .executeUpdate();
        }
    }

    public Optional<Integer> storedLevel(String userId) {
        List<?> rows = entityManager.createNativeQuery("select level_no from learning_hub.learner_levels"
            + " where user_id = :user").setParameter("user", userId).getResultList();
        return rows.stream().map(row -> ((Number) row).intValue()).findFirst();
    }

    /** Nâng cấp đã lưu lên level nếu cao hơn; không bao giờ hạ. Trả cấp đã lưu sau thao tác. */
    public int raise(String userId, int level) {
        return ((Number) entityManager.createNativeQuery("""
            insert into learning_hub.learner_levels (user_id, level_no)
            values (:user, :level)
            on conflict (user_id) do update
              set level_no = greatest(learning_hub.learner_levels.level_no, excluded.level_no),
                  reached_at = case when excluded.level_no > learning_hub.learner_levels.level_no then now()
                                    else learning_hub.learner_levels.reached_at end,
                  update_time = now()
            returning level_no
            """).setParameter("user", userId).setParameter("level", level).getSingleResult()).intValue();
    }

    /** Tổng XP của mọi người có dòng trong sổ. */
    public Map<String, Long> totals() {
        List<?> rows = entityManager.createNativeQuery("""
            select user_id, sum(amount) from learning_hub.xp_ledger group by user_id
            """).getResultList();
        return rows.stream().map(Object[].class::cast)
            .collect(Collectors.toMap(row -> (String) row[0], row -> ((Number) row[1]).longValue()));
    }
}
