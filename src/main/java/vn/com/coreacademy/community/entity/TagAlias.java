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

/** Tên đồng nghĩa để tìm tag, như Spring Boot/SpringBoot; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_tag_aliases", schema = "learning_hub")
public class TagAlias extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "alias_id", nullable = false)
    private Long aliasId;

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    @Column(name = "normalized_alias", nullable = false, length = 100)
    private String normalizedAlias;
}
