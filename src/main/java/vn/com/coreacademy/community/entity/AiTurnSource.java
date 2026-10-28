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

/** Nguồn chính xác của một lượt AI, kiểm quyền lại khi xem; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_ai_turn_sources", schema = "learning_hub")
public class AiTurnSource extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "turn_id", nullable = false)
    private Long turnId;

    @Column(name = "community_chunk_id")
    private Long communityChunkId;

    @Column(name = "knowledge_chunk_id")
    private Long knowledgeChunkId;

    @Column(name = "course_revision_id")
    private Long courseRevisionId;

    @Column(name = "citation_no", nullable = false)
    private Integer citationNo;

    @Column(name = "used_in_answer", nullable = false)
    private Boolean usedInAnswer;
}
