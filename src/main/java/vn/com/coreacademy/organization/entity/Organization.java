package vn.com.coreacademy.organization.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.UpdatedEntity;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "orgs", schema = "learning_hub")
public class Organization extends UpdatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "org_id", nullable = false)
    private Long id;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "org_type", length = 20, nullable = false)
    private String orgType;

    @Column(name = "code", length = 50, nullable = false)
    private String code;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "active";

    @Column(name = "sort_no", nullable = false)
    private int sortNo = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "parent_id", referencedColumnName = "org_id", insertable = false, updatable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Organization parent;
}
