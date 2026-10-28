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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Một lần dùng tệp trong đúng phiên bản; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_revision_assets", schema = "learning_hub")
public class RevisionAsset extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "revision_asset_id", nullable = false)
    private Long revisionAssetId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(name = "usage", nullable = false, length = 30)
    private String usage;

    @Column(name = "caption", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String caption;

    @Column(name = "alt_text", length = 255)
    private String altText;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;
}
