package vn.com.coreacademy.organization.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class UserOrgId implements Serializable {
    private static final long serialVersionUID = 1L;
    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;
    @Column(name = "org_id", nullable = false)
    private Long orgId;

    public UserOrgId() {}
    public UserOrgId(String userId, Long orgId) {
        this.userId = userId;
        this.orgId = orgId;
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Long getOrgId() { return orgId; }
    public void setOrgId(Long orgId) { this.orgId = orgId; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof UserOrgId that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(orgId, that.orgId);
    }
    @Override public int hashCode() { return Objects.hash(userId, orgId); }
}
