package vn.com.coreacademy.learning.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Một bài học trong chương. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "course_lessons", schema = "learning_hub")
public class CourseLesson extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lesson_id", nullable = false)
    private Long id;

    @Column(name = "chapter_id", nullable = false)
    private Long chapterId;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "lesson_type", length = 20, nullable = false)
    private String lessonType;

    @Column(name = "estimated_duration_seconds", nullable = false)
    private BigDecimal estimatedDurationSeconds;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    /**
     * Bài học gốc mà bài này được nhân bản từ đó. Mỗi phiên bản nhân bản có lesson_id mới, nên "cùng một bài qua các
     * phiên bản" chỉ nhận ra được qua cột này. Định danh của bài = coalesce(source_lesson_id, lesson_id). Cột luôn
     * trỏ tới bài gốc đầu tiên, không trỏ tới bản sao liền trước: khi nhân bản bản nháp của cùng khóa, bài mới nhận
     * coalesce(source.sourceLessonId, source.id), nên một bước nối là đủ, không cần đệ quy. Bài sao chép sang khóa
     * khác (copy) là bài mới và để trống. Bài của phiên bản tạo trước khi cột được ghi có giá trị null và tính là bài
     * riêng.
     */
    @Column(name = "source_lesson_id")
    private Long sourceLessonId;

    /** Bài kiểm tra của bài học loại quiz (ID logic, cùng khóa học); null với bài nội dung. */
    @Column(name = "quiz_id")
    private Long quizId;

    /** Gói SCORM bất biến được gắn vào bài học; kết quả thuộc từng lần ghi danh. */
    @Column(name = "scorm_package_id")
    private Long scormPackageId;

    /** Điều kiện theo trạng thái SCO, không suy từ điểm raw; null ở bài không phải SCORM. */
    @Column(name = "scorm_completion_rule", length = 24)
    private String scormCompletionRule;

    /**
     * Bài do AI viết: pending | writing | written | user_edited (người dùng đã sửa, job không ghi đè); null nếu bài
     * không do AI quản lý. Không được nhân bản sang phiên bản khác.
     */
    @Column(name = "ai_state", length = 12)
    private String aiState;
}
