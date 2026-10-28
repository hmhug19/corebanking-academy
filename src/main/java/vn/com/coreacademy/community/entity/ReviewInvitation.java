package vn.com.coreacademy.community.entity;

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

/** Mời một người review; theo dõi kết quả riêng; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_review_invitations", schema = "learning_hub")
public class ReviewInvitation extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invitation_id", nullable = false)
    private Long invitationId;

    @Column(name = "proposal_content_id", nullable = false)
    private Long proposalContentId;

    @Column(name = "reviewer_user_id", nullable = false, length = 20)
    private String reviewerUserId;

    @Column(name = "invited_by", nullable = false, length = 20)
    private String invitedBy;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;
}
