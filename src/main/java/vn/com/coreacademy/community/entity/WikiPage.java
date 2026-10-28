package vn.com.coreacademy.community.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Vị trí trang Wiki và trách nhiệm duy trì; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_wiki_pages", schema = "learning_hub")
public class WikiPage extends CommunityRecord {
    @Id
    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "guild_id", nullable = false)
    private Long guildId;

    @Column(name = "parent_page_content_id")
    private Long parentPageContentId;

    @Column(name = "slug", nullable = false, length = 100)
    private String slug;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    @Column(name = "review_due_at")
    private LocalDate reviewDueAt;
}
