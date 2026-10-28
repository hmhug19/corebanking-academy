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

/** Một người được nhắc tên trong revision hoặc bình luận; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_mentions", schema = "learning_hub")
public class CommunityMention extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "mention_id", nullable = false)
    private Long mentionId;

    @Column(name = "mentioned_user_id", nullable = false, length = 20)
    private String mentionedUserId;

    @Column(name = "revision_id")
    private Long revisionId;

    @Column(name = "comment_id")
    private Long commentId;
}
