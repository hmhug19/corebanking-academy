package vn.com.coreacademy.organization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class UserTeamId implements Serializable {
    private static final long serialVersionUID = 1L;
    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    public UserTeamId() {}
    public UserTeamId(String userId, Long teamId) {
        this.userId = userId;
        this.teamId = teamId;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UserTeamId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(teamId, that.teamId);
    }
    @Override public int hashCode() { return Objects.hash(userId, teamId); }
}
