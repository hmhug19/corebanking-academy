package vn.com.coreacademy.community.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Một hội chuyên môn thuộc đơn vị; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_guilds", schema = "learning_hub")
public class Guild extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "owner_org_id", nullable = false)
    private Long ownerOrgId;

    @Column(name = "slug", nullable = false, length = 100)
    private String slug;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String description;

    @Column(name = "owner_user_id", nullable = false, length = 20)
    private String ownerUserId;

    @Column(name = "visibility", nullable = false, length = 30)
    private String visibility;

    @Column(name = "join_policy", nullable = false, length = 30)
    private String joinPolicy;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "client_request_id", nullable = false)
    private UUID clientRequestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
}
