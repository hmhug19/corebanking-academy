package vn.com.coreacademy.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Ghi chú riêng của học viên trên một bài học, neo vào cả bài, một trang PDF hoặc một giây video. Xóa mềm bằng
 * isDeleted. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý; ghi chú ở lại bài và khối nơi nó được viết.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "lesson_notes", schema = "learning_hub")
public class LessonNote extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "note_id", nullable = false)
    private Long id;

    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    /** Null khi anchorType là none. */
    @Column(name = "block_id")
    private Long blockId;

    @Column(name = "anchor_type", length = 10, nullable = false)
    private String anchorType;

    @Column(name = "pdf_page")
    private Integer pdfPage;

    /** Giây nguyên, không phải giây thập phân, để so sánh neo bằng nhau. */
    @Column(name = "video_second")
    private Integer videoSecond;

    @Column(name = "body", columnDefinition = "text", nullable = false)
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String body;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;
}
