package vn.com.coreacademy.identity.entity;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import vn.com.coreacademy.common.persistence.UpdatedEntity;
import java.time.Instant;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.converter.UserStatusConverter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "users", schema = "learning_hub")
public class UserAccount extends UpdatedEntity {
    @Id
    @Column(name = "user_id", length = 20, nullable = false)
    private String id;

    @Column(name = "user_name", length = 100, nullable = false, unique = true)
    private String username;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "full_name", length = 255, nullable = false)
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

    @Column(name = "status", length = 20, nullable = false)
    @Convert(converter = UserStatusConverter.class)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "auth_ver", nullable = false)
    private long authorizationVersion = 0;

    @Column(name = "last_sync_time")
    private Instant lastSyncTime;

    @Column(name = "org_id")
    private Long orgId;
    @Column(name = "team_id")
    private Long teamId;
    @Column(name = "position_name", length = 255)
    private String positionName;
    @Column(name = "auth_provider", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private vn.com.coreacademy.identity.enums.AuthProvider authProvider = vn.com.coreacademy.identity.enums.AuthProvider.LOCAL;
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "password_hash", length = 255)
    private String passwordHash;
    @Column(name = "password_changed_time")
    private Instant passwordChangedTime;
}
