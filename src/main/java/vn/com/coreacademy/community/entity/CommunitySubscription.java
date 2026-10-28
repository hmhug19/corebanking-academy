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

/** Theo dõi một nội dung/tag/Guild/tác giả; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_subscriptions", schema = "learning_hub")
public class CommunitySubscription extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "content_id")
    private Long contentId;

    @Column(name = "tag_id")
    private Long tagId;

    @Column(name = "guild_id")
    private Long guildId;

    @Column(name = "author_user_id", length = 20)
    private String authorUserId;

    @Column(name = "delivery_mode", nullable = false, length = 30)
    private String deliveryMode;
}
