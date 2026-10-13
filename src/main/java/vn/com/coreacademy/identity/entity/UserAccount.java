package vn.com.coreacademy.identity.entity;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.UpdatedEntity;
import java.time.Instant;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.converter.UserStatusConverter;

@Entity
@Table(name = "users", schema = "learning_hub", uniqueConstraints = @UniqueConstraint(name = "uq_users_id_name", columnNames = {"user_id", "user_name"}))
public class UserAccount extends UpdatedEntity {
    @Id
    @Column(name = "user_id", length = 20, nullable = false)
    private String id;

    @Column(name = "user_name", length = 100, nullable = false, unique = true)
    private String username;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "full_name", length = 255)
    private String fullName;

    @Column(name = "brn", length = 20)
    private String brn;

    @Column(name = "brcd", length = 20)
    private String brcd;

    @Column(name = "cra", length = 20)
    private String cra;

    @Column(name = "brn_name", length = 255)
    private String brnName;

    @Column(name = "brcd_name", length = 255)
    private String brcdName;

    @Column(name = "cra_name", length = 255)
    private String craName;

    @Column(name = "dept_code", length = 50)
    private String deptCode;

    @Column(name = "dept_name", length = 255)
    private String deptName;

    @Column(name = "bds_code", length = 20)
    private String bdsCode;

    @Column(name = "bds_name", length = 255)
    private String bdsName;

    @Column(name = "qlns_brn_code", length = 20)
    private String qlnsBrnCode;

    @Column(name = "status", length = 10, nullable = false)
    @Convert(converter = UserStatusConverter.class)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "auth_ver", nullable = false)
    private long authorizationVersion = 0;

    @Column(name = "last_sync_time")
    private Instant lastSyncTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getBrn() { return brn; }
    public void setBrn(String brn) { this.brn = brn; }

    public String getBrcd() { return brcd; }
    public void setBrcd(String brcd) { this.brcd = brcd; }

    public String getCra() { return cra; }
    public void setCra(String cra) { this.cra = cra; }

    public String getBrnName() { return brnName; }
    public void setBrnName(String brnName) { this.brnName = brnName; }

    public String getBrcdName() { return brcdName; }
    public void setBrcdName(String brcdName) { this.brcdName = brcdName; }

    public String getCraName() { return craName; }
    public void setCraName(String craName) { this.craName = craName; }

    public String getDeptCode() { return deptCode; }
    public void setDeptCode(String deptCode) { this.deptCode = deptCode; }

    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }

    public String getBdsCode() { return bdsCode; }
    public void setBdsCode(String bdsCode) { this.bdsCode = bdsCode; }

    public String getBdsName() { return bdsName; }
    public void setBdsName(String bdsName) { this.bdsName = bdsName; }

    public String getQlnsBrnCode() { return qlnsBrnCode; }
    public void setQlnsBrnCode(String qlnsBrnCode) { this.qlnsBrnCode = qlnsBrnCode; }

    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }

    public long getAuthorizationVersion() { return authorizationVersion; }
    public void setAuthorizationVersion(long authorizationVersion) { this.authorizationVersion = authorizationVersion; }

    public Instant getLastSyncTime() { return lastSyncTime; }
    public void setLastSyncTime(Instant lastSyncTime) { this.lastSyncTime = lastSyncTime; }

}
