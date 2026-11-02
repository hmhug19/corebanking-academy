package vn.com.coreacademy.learning.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.learning.constants.LearningCodes.FeedbackKind;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.RatingSummary;

/**
 * Truy vấn đọc tổng hợp của phản hồi: điểm đánh giá của bài và của khóa, nhận xét kèm tên người gửi. Chỉ tính dòng
 * còn sống. Phân trang ở DB.
 */
@Repository
@RequiredArgsConstructor
public class LessonFeedbackReadRepository {
    private final EntityManager entityManager;

    /** Một nhận xét đã join với tên hiển thị của người gửi. */
    public record CommentRow(Long id, String userId, String authorName, String text, String anchorType, Long blockId,
                             Integer pdfPage, Integer videoSecond, Instant createTime) {
    }

    /**
     * Điểm đánh giá của bài: trung bình làm tròn một chữ số và số đánh giá, qua mọi người xem bất kể phiên bản khóa
     * họ đang học. Null khi bài chưa có đánh giá.
     */
    public RatingSummary lessonRating(Long lessonId) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            select round(avg(stars), 1), count(*)
            from learning_hub.lesson_feedback
            where lesson_id = :lessonId and kind = :rating and not is_deleted
            """)
            .setParameter("lessonId", lessonId)
            .setParameter("rating", FeedbackKind.RATING)
            .getSingleResult();
        long count = ((Number) row[1]).longValue();
        return count == 0 ? null : new RatingSummary((BigDecimal) row[0], count);
    }

    /**
     * Điểm đánh giá của các khóa trong trang bằng một truy vấn gom nhóm, qua mọi phiên bản của khóa; khóa chưa có
     * đánh giá không có mặt trong kết quả.
     */
    public Map<Long, RatingSummary> courseRatings(Collection<Long> courseIds) {
        Map<Long, RatingSummary> ratings = new HashMap<>();
        if (courseIds.isEmpty()) {
            return ratings;
        }
        List<?> rows = entityManager.createNativeQuery("""
            select course_id, round(avg(stars), 1), count(*)
            from learning_hub.lesson_feedback
            where course_id in (:courseIds) and kind = :rating and not is_deleted
            group by course_id
            """)
            .setParameter("courseIds", courseIds)
            .setParameter("rating", FeedbackKind.RATING)
            .getResultList();
        rows.forEach(row -> {
            Object[] v = (Object[]) row;
            ratings.put(((Number) v[0]).longValue(), new RatingSummary((BigDecimal) v[1],
                ((Number) v[2]).longValue()));
        });
        return ratings;
    }

    public long commentCount(Long lessonId) {
        return ((Number) entityManager.createNativeQuery("""
            select count(*) from learning_hub.lesson_feedback
            where lesson_id = :lessonId and kind = :comment and not is_deleted
            """)
            .setParameter("lessonId", lessonId)
            .setParameter("comment", FeedbackKind.COMMENT)
            .getSingleResult()).longValue();
    }

    /** Nhận xét sống của bài, mới nhất trước, kèm họ tên người gửi; người gửi không còn tra được thì tên là null. */
    public List<CommentRow> comments(Long lessonId, int offset, int limit) {
        List<?> rows = entityManager.createNativeQuery("""
            select f.feedback_id, f.user_id, u.full_name, f.body, f.anchor_type, f.block_id, f.pdf_page,
                   f.video_second, f.create_time
            from learning_hub.lesson_feedback f
            left join learning_hub.users u on u.user_id = f.user_id
            where f.lesson_id = :lessonId and f.kind = :comment and not f.is_deleted
            order by f.create_time desc, f.feedback_id desc
            """)
            .setParameter("lessonId", lessonId)
            .setParameter("comment", FeedbackKind.COMMENT)
            .setFirstResult(offset)
            .setMaxResults(limit)
            .getResultList();
        return rows.stream().map(row -> {
            Object[] v = (Object[]) row;
            return new CommentRow(((Number) v[0]).longValue(), (String) v[1], (String) v[2], (String) v[3],
                (String) v[4], v[5] == null ? null : ((Number) v[5]).longValue(),
                v[6] == null ? null : ((Number) v[6]).intValue(), v[7] == null ? null : ((Number) v[7]).intValue(),
                instant(v[8]));
        }).toList();
    }

    /** Hibernate trả timestamptz của truy vấn native tùy cấu hình JDBC; chấp nhận cả ba kiểu thường gặp. */
    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        throw new IllegalStateException("Unexpected timestamp type " + value.getClass().getName());
    }
}
