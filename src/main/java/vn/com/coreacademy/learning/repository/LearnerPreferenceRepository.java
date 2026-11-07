package vn.com.coreacademy.learning.repository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Tùy chọn của học viên về gamification: hiện chỉ có ẩn tên khỏi bảng xếp hạng. Dùng SQL thuần. */
@Repository
@RequiredArgsConstructor
public class LearnerPreferenceRepository {
    private final EntityManager entityManager;

    /** Trong ids, những người đã ẩn tên; rỗng nếu ids rỗng. */
    public Set<String> hiddenAmong(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<String> hidden = new HashSet<>();
        for (Object row : entityManager.createNativeQuery("""
            select user_id from learning_hub.learner_preferences
            where leaderboard_hidden and user_id in (:ids)
            """).setParameter("ids", ids).getResultList()) {
            hidden.add((String) row);
        }
        return hidden;
    }

    public boolean isHidden(String userId) {
        return !hiddenAmong(Set.of(userId)).isEmpty();
    }

    /** Ghi tùy chọn ẩn tên của một người; ghi lại cùng giá trị không đổi gì. */
    public void setHidden(String userId, boolean hidden) {
        entityManager.createNativeQuery("""
            insert into learning_hub.learner_preferences (user_id, leaderboard_hidden, update_time)
            values (:userId, :hidden, now())
            on conflict (user_id) do update
                set leaderboard_hidden = excluded.leaderboard_hidden, update_time = now()
            """).setParameter("userId", userId).setParameter("hidden", hidden).executeUpdate();
    }
}
