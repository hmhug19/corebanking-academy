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

/** Một Guild giới thiệu một nội dung trong feed; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_content_guilds", schema = "learning_hub")
public class ContentGuild extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "content_guild_id", nullable = false)
    private Long contentGuildId;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "is_pinned", nullable = false)
    private Boolean isPinned;
}
