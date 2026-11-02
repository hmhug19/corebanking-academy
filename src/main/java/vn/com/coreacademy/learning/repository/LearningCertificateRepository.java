package vn.com.coreacademy.learning.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Các truy vấn đọc cho chứng nhận: chứng nhận là một chu kỳ ghi danh đã hoàn thành nên không có bảng riêng. Chỉ tính
 * các dòng của người dùng đã cho có trạng thái completed và completed_at khác null; tiêu đề lấy từ phiên bản khóa học
 * mà chu kỳ đã học. Phân trang thực hiện ngay trong cơ sở dữ liệu.
 */
@Repository
@RequiredArgsConstructor
public class LearningCertificateRepository {
    private static final String SELECT = """
        select e.enrollment_id, e.course_id, c.code, r.title, e.cycle_no, e.revision_id, e.user_id, e.completed_at
        from learning_hub.course_enrollments e
        join learning_hub.courses c on c.course_id = e.course_id
        join learning_hub.course_revisions r on r.revision_id = e.revision_id
        where e.user_id = :userId and e.status = 'completed' and e.completed_at is not null
        """;

    private final EntityManager entityManager;

    /** Một chu kỳ đã hoàn thành, kèm mã khóa học và tiêu đề của phiên bản khóa học đã học. */
    public record CertificateRow(Long enrollmentId, Long courseId, String courseCode, String courseTitle,
                                 Integer cycleNo, Long revisionId, String userId, Instant completedAt) {
    }

    public long count(String userId) {
        return ((Number) entityManager.createNativeQuery("""
            select count(*) from learning_hub.course_enrollments
            where user_id = :userId and status = 'completed' and completed_at is not null
            """).setParameter("userId", userId).getSingleResult()).longValue();
    }

    /** Hoàn thành gần nhất trước, rồi đến id ghi danh lớn nhất. */
    public List<CertificateRow> page(String userId, int offset, int limit) {
        return entityManager.createNativeQuery(SELECT + " order by e.completed_at desc, e.enrollment_id desc")
            .setParameter("userId", userId).setFirstResult(offset).setMaxResults(limit).getResultList().stream()
            .map(row -> row((Object[]) row)).toList();
    }

    /** Rỗng khi ghi danh không phải của người dùng, chưa hoàn thành (kể cả đã rút) hoặc không tồn tại. */
    public Optional<CertificateRow> find(String userId, Long enrollmentId) {
        return entityManager.createNativeQuery(SELECT + " and e.enrollment_id = :id")
            .setParameter("userId", userId).setParameter("id", enrollmentId).getResultList().stream()
            .map(row -> row((Object[]) row)).findFirst();
    }

    private static CertificateRow row(Object[] v) {
        return new CertificateRow(((Number) v[0]).longValue(), ((Number) v[1]).longValue(), (String) v[2],
            (String) v[3], ((Number) v[4]).intValue(), ((Number) v[5]).longValue(), (String) v[6], instant(v[7]));
    }

    /** Cột timestamptz của truy vấn native trả về một trong ba kiểu, tùy cấu hình JDBC. */
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
