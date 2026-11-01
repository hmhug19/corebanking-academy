package vn.com.coreacademy.learning.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * Truy vấn đọc cho trang Đã lưu: ghi chú hoặc đánh dấu còn sống của một người, mới nhất trước, kèm tên khóa và bài
 * học. Bài học không còn tra được vẫn được liệt kê (tên là null) để người dùng xóa được. Phân trang ở DB.
 */
@Repository
@RequiredArgsConstructor
public class LessonSavedRepository {
    private final EntityManager entityManager;

    /** Một dòng đã join; text hoặc label chỉ một trong hai có giá trị tùy truy vấn. */
    public record SavedRow(Long id, Long lessonId, String anchorType, Long blockId, Integer pdfPage,
                           Integer videoSecond, String text, String label, Instant createTime, Long courseId,
                           String courseTitle, String lessonTitle) {
    }

    public record Counts(long notes, long bookmarks) {
    }

    public List<SavedRow> notes(String userId, int offset, int limit) {
        return rows(entityManager.createNativeQuery("""
            select n.note_id, n.lesson_id, n.anchor_type, n.block_id, n.pdf_page, n.video_second, n.body,
                   cast(null as varchar), n.create_time, r.course_id, coalesce(r.title, c.code), l.title
            from learning_hub.lesson_notes n
            left join learning_hub.course_lessons l on l.lesson_id = n.lesson_id
            left join learning_hub.course_chapters ch on ch.chapter_id = l.chapter_id
            left join learning_hub.course_revisions r on r.revision_id = ch.revision_id
            left join learning_hub.courses c on c.course_id = r.course_id
            where n.user_id = :userId and not n.is_deleted
            order by n.create_time desc, n.note_id desc
            """).setParameter("userId", userId).setFirstResult(offset).setMaxResults(limit).getResultList());
    }

    public List<SavedRow> bookmarks(String userId, int offset, int limit) {
        return rows(entityManager.createNativeQuery("""
            select b.bookmark_id, b.lesson_id, b.anchor_type, b.block_id, b.pdf_page, b.video_second,
                   cast(null as text), b.label, b.create_time, r.course_id, coalesce(r.title, c.code), l.title
            from learning_hub.lesson_bookmarks b
            left join learning_hub.course_lessons l on l.lesson_id = b.lesson_id
            left join learning_hub.course_chapters ch on ch.chapter_id = l.chapter_id
            left join learning_hub.course_revisions r on r.revision_id = ch.revision_id
            left join learning_hub.courses c on c.course_id = r.course_id
            where b.user_id = :userId and not b.is_deleted
            order by b.create_time desc, b.bookmark_id desc
            """).setParameter("userId", userId).setFirstResult(offset).setMaxResults(limit).getResultList());
    }

    /** Số ghi chú và số đánh dấu còn sống của cả hai tab, dùng cho tổng của trang và nhãn tab. */
    public Counts counts(String userId) {
        Object[] row = (Object[]) entityManager.createNativeQuery("""
            select (select count(*) from learning_hub.lesson_notes where user_id = :userId and not is_deleted),
                   (select count(*) from learning_hub.lesson_bookmarks where user_id = :userId and not is_deleted)
            """).setParameter("userId", userId).getSingleResult();
        return new Counts(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }

    private static List<SavedRow> rows(List<?> found) {
        return found.stream().map(row -> {
            Object[] v = (Object[]) row;
            return new SavedRow(((Number) v[0]).longValue(), ((Number) v[1]).longValue(), (String) v[2],
                v[3] == null ? null : ((Number) v[3]).longValue(), v[4] == null ? null : ((Number) v[4]).intValue(),
                v[5] == null ? null : ((Number) v[5]).intValue(), (String) v[6], (String) v[7],
                instant(v[8]), v[9] == null ? null : ((Number) v[9]).longValue(), (String) v[10],
                (String) v[11]);
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
