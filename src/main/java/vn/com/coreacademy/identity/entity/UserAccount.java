package vn.com.coreacademy.identity.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.com.coreacademy.common.persistence.UpdatedEntity;
import vn.com.coreacademy.identity.converter.UserStatusConverter;
import vn.com.coreacademy.identity.enums.AuthProvider;
import vn.com.coreacademy.identity.enums.UserStatus;

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

    // Metadata từ nguồn; không suy cây tổ chức hoặc quyền quản trị từ BRN/BRCD/CRA.
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

    // Tăng phiên bản để thu hồi JWT có claim av cũ, không cần lưu từng token.
    @Column(name = "auth_ver", nullable = false)
    private long authorizationVersion = 0;

    @Column(name = "last_sync_time")
    private Instant lastSyncTime;

    // Tổ chức/nhóm nội bộ; service kiểm tra quan hệ logic vì schema không có FK vật lý.
    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "position_name", length = 255)
    private String positionName;

    @Column(name = "auth_provider", length = 20, nullable = false)
    @Enumerated(EnumType.STRING)
    private AuthProvider authProvider = AuthProvider.LOCAL;

    @JsonIgnore
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    // Cho phép đăng nhập để đổi mật khẩu tạm, nhưng chặn quản trị cho tới khi đổi xong.
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "password_changed_time")
    private Instant passwordChangedTime;
}
