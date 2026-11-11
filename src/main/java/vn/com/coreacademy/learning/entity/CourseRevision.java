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

/** Bản nội dung; chỉ draft được sửa. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "course_revisions", schema = "learning_hub")
public class CourseRevision extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "revision_id", nullable = false)
    private Long id;

    @Column(name = "course_id", nullable = false)
    private Long courseId;

    @Column(name = "revision_no", nullable = false)
    private Integer revisionNo;

    @Column(name = "state", length = 20, nullable = false)
    private String state;

    @Column(name = "based_on_revision_id")
    private Long basedOnRevisionId;

    @Column(name = "program_id")
    private Long programId;

    @Column(name = "module_id")
    private Long moduleId;

    @Column(name = "title", length = 255)
    private String title;

    @Column(name = "summary", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String summary;

    @Column(name = "level", length = 20)
    private String level;

    @Column(name = "default_requirement", length = 20, nullable = false)
    private String defaultRequirement;

    @Column(name = "cover_asset_id")
    private Long coverAssetId;

    @Column(name = "cover_icon", length = 50)
    private String coverIcon;

    @Column(name = "cover_color", length = 20)
    private String coverColor;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by", length = 20)
    private String publishedBy;

    /** The content was written by the AI course generation; publishing needs the author's review confirmation. */
    @Column(name = "ai_generated", nullable = false)
    private boolean aiGenerated;

    /** Who confirmed they reviewed the AI-generated content when this revision was published. */
    @Column(name = "review_confirmed_by", length = 20)
    private String reviewConfirmedBy;

    @Column(name = "review_confirmed_at")
    private Instant reviewConfirmedAt;
}
