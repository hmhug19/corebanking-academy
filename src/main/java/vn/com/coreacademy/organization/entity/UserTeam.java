package vn.com.coreacademy.organization.entity;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.UpdatedEntity;
import vn.com.coreacademy.identity.entity.UserAccount;

@Entity
@Table(name = "user_teams", schema = "learning_hub")
public class UserTeam extends UpdatedEntity {
    @EmbeddedId
    private UserTeamId id;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", referencedColumnName = "user_id", insertable = false, updatable = false)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", referencedColumnName = "team_id", insertable = false, updatable = false)
    private Team team;

    public UserTeamId getId() { return id; }
    public void setId(UserTeamId id) { this.id = id; }

    public boolean isPrimary() { return primary; }
    public void setPrimary(boolean primary) { this.primary = primary; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }

    public Team getTeam() { return team; }
    public void setTeam(Team team) { this.team = team; }

}
