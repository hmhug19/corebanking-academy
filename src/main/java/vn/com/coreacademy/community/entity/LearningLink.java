package vn.com.coreacademy.community.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Một tài liệu Community được ghim vào phiên bản khóa học; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_learning_links", schema = "learning_hub")
public class LearningLink extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "learning_link_id", nullable = false)
    private Long learningLinkId;

    @Column(name = "course_revision_id", nullable = false)
    private Long courseRevisionId;

    @Column(name = "lesson_id")
    private Long lessonId;

    @Column(name = "content_revision_id", nullable = false)
    private Long contentRevisionId;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    @Column(name = "status", nullable = false, length = 30)
    private String status;
}
