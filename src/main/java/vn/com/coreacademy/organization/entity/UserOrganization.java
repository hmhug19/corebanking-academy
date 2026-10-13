package vn.com.coreacademy.organization.entity;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.UpdatedEntity;
import vn.com.coreacademy.identity.entity.UserAccount;

@Entity
@Table(name = "user_orgs", schema = "learning_hub")
public class UserOrganization extends UpdatedEntity {
    @EmbeddedId
    private UserOrgId id;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", insertable = false, updatable = false)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_id", referencedColumnName = "org_id", insertable = false, updatable = false)
    private Organization organization;

    public UserOrgId getId() { return id; }
    public void setId(UserOrgId id) { this.id = id; }

    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }

    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }

}
