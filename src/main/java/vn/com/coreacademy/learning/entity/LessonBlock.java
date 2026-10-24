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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Một khối nội dung có thứ tự trong bài học. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "lesson_blocks", schema = "learning_hub")
public class LessonBlock extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "block_id", nullable = false)
    private Long id;

    @Column(name = "lesson_id", nullable = false)
    private Long lessonId;

    @Column(name = "block_type", length = 20, nullable = false)
    private String blockType;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "body", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String body;

    @Column(name = "body_format", length = 20)
    private String bodyFormat;

    @Column(name = "completion_mode", length = 20, nullable = false)
    private String completionMode;

    @Column(name = "completion_threshold")
    private BigDecimal completionThreshold;

    @Column(name = "is_required", nullable = false)
    private Boolean isRequired;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    @Column(name = "source_block_id")
    private Long sourceBlockId;

    @Column(name = "media_asset_id")
    private Long mediaAssetId;
}
