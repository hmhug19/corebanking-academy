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

/** Membership hiện tại, kèm thời điểm tham gia/rời; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_guild_members", schema = "learning_hub")
public class GuildMember extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "membership_id", nullable = false)
    private Long membershipId;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "member_role", nullable = false, length = 30)
    private String memberRole;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "joined_at")
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    @Column(name = "approved_by", length = 20)
    private String approvedBy;

    @Column(name = "invited_by", length = 20)
    private String invitedBy;

    @Column(name = "invited_at")
    private Instant invitedAt;

    @Column(name = "invitation_expires_at")
    private Instant invitationExpiresAt;
}
