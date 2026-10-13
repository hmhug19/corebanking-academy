package vn.com.coreacademy.identity.entity;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.UpdatedEntity;
import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "user_credentials", schema = "learning_hub")
public class UserCredential extends UpdatedEntity {
    @Id
    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "user_name", length = 100, nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", length = 255, nullable = false)
    @JsonIgnore
    private String passwordHash;

    @Column(name = "password_changed_time", nullable = false)
    private Instant passwordChangedTime = Instant.now();

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
        @JoinColumn(name = "user_id", referencedColumnName = "user_id", insertable = false, updatable = false),
        @JoinColumn(name = "user_name", referencedColumnName = "user_name", insertable = false, updatable = false)
    })
    private UserAccount user;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public Instant getPasswordChangedTime() { return passwordChangedTime; }
    public void setPasswordChangedTime(Instant passwordChangedTime) { this.passwordChangedTime = passwordChangedTime; }

    public UserAccount getUser() { return user; }
    public void setUser(UserAccount user) { this.user = user; }

}
