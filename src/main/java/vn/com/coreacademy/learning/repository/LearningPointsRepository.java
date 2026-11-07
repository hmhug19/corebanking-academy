package vn.com.coreacademy.learning.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Tổng XP theo người và theo loại nguồn, đọc từ sổ ghi XP: một truy vấn trên danh sách người, không truy vấn theo từng
 * người. Người chưa có dòng nào không xuất hiện trong kết quả. Chỉ đọc.
 */
@Repository
@RequiredArgsConstructor
public class LearningPointsRepository {
    private final EntityManager entityManager;

    /** Người → (loại nguồn → tổng XP); dòng ADJUSTMENT có thể âm. */
    public Map<String, Map<String, Long>> sumsByKind(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<?> rows = entityManager.createNativeQuery("""
            select user_id, source_kind, sum(amount)
            from learning_hub.xp_ledger
            where user_id in (:users)
            group by user_id, source_kind
            """).setParameter("users", userIds).getResultList();
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            result.computeIfAbsent((String) columns[0], key -> new HashMap<>())
                .put((String) columns[1], ((Number) columns[2]).longValue());
        }
        return result;
    }
}
