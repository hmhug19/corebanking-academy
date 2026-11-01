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

/**
 * Đánh dấu riêng của học viên trên một trang PDF hoặc một giây video. Xóa mềm bằng isDeleted. Một đánh dấu sống mỗi
 * người, khối và vị trí do LearningNotesService bảo đảm dưới khóa advisory, không UNIQUE. Các cột ID liên kết là
 * scalar, không tạo khóa ngoại vật lý.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "lesson_bookmarks", schema = "learning_hub")
public class LessonBookmark extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bookmark_id", nullable = false)
    private Long id;

    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    @Column(name = "block_id", nullable = false)
    private Long blockId;

    @Column(name = "anchor_type", length = 10, nullable = false)
    private String anchorType;

    @Column(name = "pdf_page")
    private Integer pdfPage;

    /** Giây nguyên, không phải giây thập phân, để so sánh neo bằng nhau. */
    @Column(name = "video_second")
    private Integer videoSecond;

    @Column(name = "label", length = 200)
    private String label;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted = false;
}
