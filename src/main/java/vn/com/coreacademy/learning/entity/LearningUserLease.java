package vn.com.coreacademy.learning.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "learning_user_leases", schema = "learning_hub")
public class LearningUserLease extends LearningRecord {
    @Id
    @Column(name = "user_id", length = 20)
    private String userId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "lease_state", length = 12)
    private String leaseState;

    @Column(name = "enrollment_id")
    private Long enrollmentId;

    @Column(name = "block_id")
    private Long blockId;

    @Column(name = "media_asset_id")
    private Long mediaAssetId;

    @Column(name = "session_fence")
    private Long sessionFence;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "last_event_sequence")
    private Long lastEventSequence;

    @Column(name = "last_event_hash", length = 64)
    private String lastEventHash;

    @Column(name = "last_event_kind", length = 24)
    private String lastEventKind;

    @Column(name = "last_event_accepted_at")
    private Instant lastEventAcceptedAt;

}
