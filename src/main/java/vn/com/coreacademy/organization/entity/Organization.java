package vn.com.coreacademy.organization.entity;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.UpdatedEntity;


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

    @Column(name = "status", length = 10, nullable = false)
    private String status = "active";

    @Column(name = "sort_no", nullable = false)
    private int sortNo = 0;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "parent_id", referencedColumnName = "org_id", insertable = false, updatable = false)
    private Organization parent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public String getOrgType() { return orgType; }
    public void setOrgType(String orgType) { this.orgType = orgType; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getSortNo() { return sortNo; }
    public void setSortNo(int sortNo) { this.sortNo = sortNo; }

    public Organization getParent() { return parent; }
    public void setParent(Organization parent) { this.parent = parent; }

}
