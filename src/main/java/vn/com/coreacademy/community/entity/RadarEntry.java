package vn.com.coreacademy.community.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Một đề xuất công nghệ có giai đoạn đánh giá; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_radar_entries", schema = "learning_hub")
public class RadarEntry extends CommunityRecord {
    @Id
    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "review_due_at")
    private LocalDate reviewDueAt;
}
