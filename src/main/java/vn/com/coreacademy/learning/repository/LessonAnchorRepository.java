package vn.com.coreacademy.learning.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetUsage;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;

/** Tra cứu quyền truy cập bài học và giới hạn tài nguyên của khối để kiểm neo ghi chú, đánh dấu. Chỉ đọc. */
@Repository
@RequiredArgsConstructor
public class LessonAnchorRepository {
    private final EntityManager entityManager;

    /**
     * Khối của bài kèm giới hạn của tài nguyên chính. Tài nguyên chính là media_asset_id của khối, nếu trống thì là
     * tài nguyên primary đầu tiên của khối; tài nguyên chưa sẵn sàng hoặc đã xóa coi như không có giới hạn.
     */
    public record BlockBounds(String blockType, String title, Integer pageCount, BigDecimal durationSeconds) {
    }

    /**
     * Bài học có thuộc phiên bản của một lần học của người dùng hay không, bằng một truy vấn; lần học đã rút
     * (withdrawn) không còn quyền như khi mở bài.
     */
    public boolean lessonVisibleTo(String userId, Long lessonId) {
        return Boolean.TRUE.equals(entityManager.createNativeQuery("""
            select exists (
                select 1
                from learning_hub.course_lessons l
                join learning_hub.course_chapters c on c.chapter_id = l.chapter_id
                join learning_hub.course_enrollments e on e.revision_id = c.revision_id
                where l.lesson_id = :lessonId and e.user_id = :userId and e.status <> :withdrawn)
            """)
            .setParameter("lessonId", lessonId)
            .setParameter("userId", userId)
            .setParameter("withdrawn", ProgressStatus.WITHDRAWN)
            .getSingleResult());
    }

    /** Rỗng nếu khối không thuộc bài học. */
    public Optional<BlockBounds> block(Long lessonId, Long blockId) {
        List<?> rows = entityManager.createNativeQuery("""
            select b.block_type, b.title, a.page_count, a.duration_seconds
            from learning_hub.lesson_blocks b
            left join learning_hub.assets a
              on a.asset_id = coalesce(b.media_asset_id,
                     (select ba.asset_id from learning_hub.block_assets ba
                      where ba.block_id = b.block_id and ba.usage = :primary
                      order by ba.sort_no, ba.block_asset_id limit 1))
             and a.status = :ready and not a.is_deleted
            where b.block_id = :blockId and b.lesson_id = :lessonId
            """)
            .setParameter("primary", AssetUsage.PRIMARY)
            .setParameter("ready", AssetStatus.READY)
            .setParameter("blockId", blockId)
            .setParameter("lessonId", lessonId)
            .getResultList();
        return rows.stream().findFirst().map(row -> {
            Object[] values = (Object[]) row;
            return new BlockBounds((String) values[0], (String) values[1],
                values[2] == null ? null : ((Number) values[2]).intValue(), (BigDecimal) values[3]);
        });
    }
}
