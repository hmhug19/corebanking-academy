package vn.com.coreacademy.learning.entity;

import java.time.Instant;

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
 * Phản hồi của học viên trên một bài học: đánh giá sao, nhận xét công khai, câu hỏi giảng viên hoặc báo nội dung,
 * phân biệt bằng kind. Xóa mềm bằng isDeleted. Một đánh giá sống mỗi người và bài do LearningFeedbackService bảo đảm
 * dưới khóa advisory, không UNIQUE. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "lesson_feedback", schema = "learning_hub")
public class LessonFeedback extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "feedback_id", nullable = false)
    private Long id;

    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    /** Khóa chứa bài lúc gửi, để điểm đánh giá của khóa gom được qua mọi phiên bản. */
    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "kind", length = 10, nullable = false)
    private String kind;

    /** Chỉ có khi kind là rating. */
    @Column(name = "stars")
    @JdbcTypeCode(SqlTypes.SMALLINT)
    private Integer stars;

    /** Null khi kind là rating. */
    @Column(name = "body", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String body;

    /** Null khi anchorType là none hoặc kind là rating. */
    @Column(name = "block_id")
    private Long blockId;

    /** Null khi kind là rating. */
    @Column(name = "anchor_type", length = 10)
    private String anchorType;

    @Column(name = "pdf_page")
    private Integer pdfPage;

    /** Giây nguyên, không phải giây thập phân, để so sánh neo bằng nhau. */
    @Column(name = "video_second")
    private Integer videoSecond;

    /** Chỉ có khi kind là question hoặc report; null với rating và comment. */
    @Column(name = "status", length = 10)
    private String status;

    @Column(name = "reply", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String reply;

    @Column(name = "replied_by", length = 20)
    private String repliedBy;

    @Column(name = "reply_time")
    private Instant replyTime;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;
}
