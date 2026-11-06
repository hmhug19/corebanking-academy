package vn.com.coreacademy.learning.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        // kind là literal, không bind: với plan tổng quát của prepared statement, Postgres không chứng minh được
        // predicate của index một phần idx_lesson_feedback_course_rating (kind = 'rating') nên sẽ không dùng nó.
        List<?> rows = entityManager.createNativeQuery("""
            select course_id, round(avg(stars), 1), count(*)
            from learning_hub.lesson_feedback
            where course_id in (:courseIds) and kind = 'rating' and not is_deleted
            group by course_id
            """)
            .setParameter("courseIds", courseIds)
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

    /** Một câu hỏi hoặc báo nội dung đã join với bài, khóa; tên người gửi tra riêng theo userId. */
    public record InstructorRow(Long id, String kind, String text, String anchorType, Long blockId, Integer pdfPage,
                                Integer videoSecond, String status, String reply, Instant replyTime,
                                Instant createTime, Long lessonId, String lessonTitle, Long courseId,
                                String courseCode, String courseTitle, String userId) {
    }

    /** Phạm vi khóa giảng viên quản lý, như tìm kiếm MANAGE: toàn cục, theo đơn vị, theo nhóm hoặc khóa do mình tạo. */
    public record ManageScope(boolean global, Set<Long> orgIds, Set<Long> teamIds, boolean own, String userId) {
        public boolean isEmpty() {
            return !global && !own && orgIds.isEmpty() && teamIds.isEmpty();
        }
    }

    private static final String INSTRUCTOR_FROM = """
        from learning_hub.lesson_feedback f
        join learning_hub.courses c on c.course_id = f.course_id
        join learning_hub.course_lessons l on l.lesson_id = f.lesson_id
        left join learning_hub.course_revisions r on r.revision_id = c.published_revision_id
        where not f.is_deleted and f.kind in ('question', 'report')
          and (cast(:status as varchar) is null or f.status = cast(:status as varchar))
          and (:global or c.owner_org_id in (:orgIds) or c.owner_team_id in (:teamIds)
               or (:own and c.owner_user_id = :userId))
        """;

    /** Danh sách rỗng làm Hibernate sinh `in ()`, nên thay bằng một ID không tồn tại. */
    private static Set<Long> ids(Set<Long> values) {
        return values.isEmpty() ? Set.of(-1L) : values;
    }

    /** Câu hỏi và báo nội dung trên các khóa trong phạm vi, mới nhất trước; `status` null là mọi trạng thái. */
    public List<InstructorRow> instructorFeedback(ManageScope scope, String status, int offset, int limit) {
        List<?> rows = entityManager.createNativeQuery("""
            select f.feedback_id, f.kind, f.body, f.anchor_type, f.block_id, f.pdf_page, f.video_second, f.status,
                   f.reply, f.reply_time, f.create_time, f.lesson_id, l.title, f.course_id, c.code,
                   coalesce(r.title, c.code), f.user_id
            """ + INSTRUCTOR_FROM + """
            order by f.create_time desc, f.feedback_id desc
            """)
            .setParameter("status", status)
            .setParameter("global", scope.global())
            .setParameter("orgIds", ids(scope.orgIds()))
            .setParameter("teamIds", ids(scope.teamIds()))
            .setParameter("own", scope.own())
            .setParameter("userId", scope.userId())
            .setFirstResult(offset)
            .setMaxResults(limit)
            .getResultList();
        return rows.stream().map(row -> {
            Object[] v = (Object[]) row;
            return new InstructorRow(((Number) v[0]).longValue(), (String) v[1], (String) v[2], (String) v[3],
                v[4] == null ? null : ((Number) v[4]).longValue(), v[5] == null ? null : ((Number) v[5]).intValue(),
                v[6] == null ? null : ((Number) v[6]).intValue(), (String) v[7], (String) v[8],
                v[9] == null ? null : instant(v[9]), instant(v[10]), ((Number) v[11]).longValue(), (String) v[12],
                ((Number) v[13]).longValue(), (String) v[14], (String) v[15], (String) v[16]);
        }).toList();
    }

    public long instructorFeedbackCount(ManageScope scope, String status) {
        return ((Number) entityManager.createNativeQuery("select count(*) " + INSTRUCTOR_FROM)
            .setParameter("status", status)
            .setParameter("global", scope.global())
            .setParameter("orgIds", ids(scope.orgIds()))
            .setParameter("teamIds", ids(scope.teamIds()))
            .setParameter("own", scope.own())
            .setParameter("userId", scope.userId())
            .getSingleResult()).longValue();
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
