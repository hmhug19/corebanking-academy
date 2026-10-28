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

/** Liên kết tới nội dung, khóa học hoặc tài liệu AI; ID scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_content_links", schema = "learning_hub")
public class ContentLink extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "link_id", nullable = false)
    private Long linkId;

    @Column(name = "source_content_id", nullable = false)
    private Long sourceContentId;

    @Column(name = "target_content_id")
    private Long targetContentId;

    @Column(name = "target_course_id")
    private Long targetCourseId;

    @Column(name = "target_knowledge_document_id")
    private Long targetKnowledgeDocumentId;

    @Column(name = "relation_type", nullable = false, length = 30)
    private String relationType;
}
