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

    @Column(name = "source_lesson_id")
    private Long sourceLessonId;
}
