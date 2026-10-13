package vn.com.coreacademy.organization.entity;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.UpdatedEntity;
import vn.com.coreacademy.identity.entity.UserAccount;

@Entity
@Table(name = "user_pos", schema = "learning_hub")
public class UserPosition extends UpdatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "up_id", nullable = false)
    private Long id;

    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "pos_id", nullable = false)
    private Long posId;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", insertable = false, updatable = false)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pos_id", referencedColumnName = "pos_id", insertable = false, updatable = false)
    private Position position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "org_id", referencedColumnName = "org_id", insertable = false, updatable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
        @JoinColumn(name = "team_id", referencedColumnName = "team_id", insertable = false, updatable = false),
        @JoinColumn(name = "org_id", referencedColumnName = "org_id", insertable = false, updatable = false)
    })
    private Team team;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Long getPosId() { return posId; }
    public void setPosId(Long posId) { this.posId = posId; }

    public Long getOrgId() { return orgId; }
    public void setOrgId(Long orgId) { this.orgId = orgId; }

    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }

    public Position getPosition() { return position; }
    public void setPosition(Position position) { this.position = position; }

    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }

    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }

}
