package vn.com.coreacademy.community.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Metadata retry/feedback của lượt AI, không lưu prompt/answer; ID scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_ai_turns", schema = "learning_hub")
public class CommunityAiTurn extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "turn_id", nullable = false)
    private Long turnId;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "model", nullable = false, length = 100)
    private String model;

    @Column(name = "turn_status", nullable = false, length = 30)
    private String turnStatus;

    @Column(name = "source_count", nullable = false)
    private Integer sourceCount;

    @Column(name = "duration_ms", nullable = false)
    private Long durationMs;

    @Column(name = "completed_at")
    private Instant completedAt;
}
