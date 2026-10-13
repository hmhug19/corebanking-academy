package vn.com.coreacademy.identity.entity;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.UpdatedEntity;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.entity.Team;

@Entity
@Table(name = "user_roles", schema = "learning_hub")
public class RoleAssignment extends UpdatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ur_id", nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "scope", length = 10, nullable = false)
    @Enumerated(EnumType.STRING)
    private ScopeType scopeType;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "granted_by", length = 20)
    private String grantedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "org_id", referencedColumnName = "org_id", insertable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "team_id", referencedColumnName = "team_id", insertable = false, updatable = false),
        @JoinColumn(name = "org_id", referencedColumnName = "org_id", insertable = false, updatable = false)
    })
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "granted_by", referencedColumnName = "user_id", insertable = false, updatable = false)
    private UserAccount grantor;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public ScopeType getScopeType() { return scopeType; }
    public void setScopeType(ScopeType scopeType) { this.scopeType = scopeType; }

    public Long getOrgId() { return orgId; }
    public void setOrgId(Long orgId) { this.orgId = orgId; }

    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getGrantedBy() { return grantedBy; }
    public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }

    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }

    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }

    public UserAccount getGrantor() { return grantor; }
    public void setGrantor(UserAccount grantor) { this.grantor = grantor; }

}
