package vn.com.coreacademy.identity.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.dto.FunctionInfo;
import vn.com.coreacademy.identity.dto.GrantRequest;
import vn.com.coreacademy.identity.dto.RoleOption;
import vn.com.coreacademy.identity.entity.Role;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.exception.IdentityResponseCode;
import vn.com.coreacademy.identity.repository.FunctionRepository;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.identity.repository.RoleRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.security.SecurityContextUtils;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RoleGrantService {
    private final RoleRepository roles;
    private final RoleAssignmentRepository assignments;
    private final FunctionRepository functions;
    private final UserRepository users;
    private final IdentityAccessPolicy access;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;

    /**
     * Trả vai trò đang hoạt động và các phạm vi người gọi được phép cấp. Danh sách capabilities mô tả hành
     * động theo vai trò, không thay kiểm tra scope và tài nguyên trong policy.
     */
    public List<RoleOption> catalog() {
        String actorId = SecurityContextUtils.getUserId();
        var boundary = access.boundary(actorId);

        return roles.findAll().stream().filter(Role::isActive).sorted(Comparator.comparing(Role::getCode))
            .map(role -> new RoleOption(role.getId(), role.getCode(), role.getName(), role.getDescription(),
                access.allowedScopes(boundary, role.getCode()), capabilities(role.getCode()),
                functions.findActiveForRole(role.getId()).stream()
                    .map(f -> new FunctionInfo(f.getId(), f.getCode(), f.getName(), f.getRoutePattern(),
                        f.getParentFunctionId(), f.getSortNo())).toList())).toList();
    }

    // Danh mục hành động theo vai trò; policy vẫn giới hạn theo scope và tài nguyên thực tế.
    private List<String> capabilities(String role) {
        if (Set.of(RoleCodes.ADMIN, RoleCodes.SUPER_ADMIN).contains(role)) {
            var actions = new ArrayList<>(List.of("USER_READ", "USER_CREATE", "USER_UPDATE",
                "USER_STATUS_UPDATE", "ROLE_GRANT", "ORG_READ", "ORG_CREATE", "ORG_UPDATE", "ORG_STATUS_UPDATE",
                "TEAM_READ", "TEAM_CREATE", "TEAM_UPDATE", "TEAM_DELETE"));
            if (RoleCodes.SUPER_ADMIN.equals(role)) {
                actions.add("ADMIN_ROLE_GRANT");
            }
            return List.copyOf(actions);
        }
        return List.of();
    }

    /**
     * Cấp một phân quyền cho tài khoản thuộc phạm vi quản lý, đồng thời thu hồi token cũ và ghi audit. Khóa
     * ghi được giữ trong transaction để phối hợp với kiểm tra trùng và các thao tác quản trị khác.
     */
    @Transactional
    public Long add(String targetId, GrantRequest request) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        UserAccount user = access.manageableUser(actorId, targetId);

        return addWithinTransaction(actorId, user, request).getId();
    }

    /**
     * Cấp vai trò gắn với đúng scope trong transaction hiện có, tăng auth_ver và ghi audit.
     *
     * <p>Được dùng lại khi tạo tài khoản để rollback cùng yêu cầu tạo. Bên gọi phải giữ IdentityWriteLock và
     * kiểm tra quyền quản lý tài khoản; hàm này không tự lấy khóa hoặc tạo transaction mới.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RoleAssignment addWithinTransaction(String actorId, UserAccount user, GrantRequest request) {
        if (actorId.equals(user.getId())) {
            throw new AccessDeniedException("Self role changes are not allowed");
        }

        var role = roles.findById(request.roleId()).filter(r -> r.isActive())
            .orElseThrow(() -> new BusinessException(IdentityResponseCode.INVALID_SCOPE));
        access.requireGrant(actorId, role.getCode(), request);

        // TEAM được định danh bởi teamId; gửi hoặc bỏ orgId không tạo ra hai phân quyền khác nhau.
        if (assignments.findActiveForUser(user.getId()).stream().anyMatch(g ->
            g.getRole().getId().equals(role.getId()) && g.getScopeType() == request.scope()
                && (request.scope() == ScopeType.TEAM || Objects.equals(g.getOrgId(), request.orgId()))
                && Objects.equals(g.getTeamId(), request.teamId()))) {
            throw new BusinessException(IdentityResponseCode.DUPLICATE_GRANT);
        }

        RoleAssignment grant = new RoleAssignment();
        grant.setUser(user);
        grant.setRole(role);
        grant.setScopeType(request.scope());
        grant.setOrgId(request.orgId());
        grant.setTeamId(request.teamId());
        grant.setGrantedBy(actorId);
        assignments.save(grant);

        user.setAuthorizationVersion(user.getAuthorizationVersion() + 1);
        users.save(user);

        audit.record(actorId, "USER_ROLE_GRANTED", "USER", user.getId(), user.getOrgId(),
            Map.of("roleCode", role.getCode(), "scope", request.scope().name(), "grantId", grant.getId()));

        return grant;
    }

    /**
     * Vô hiệu hóa phân quyền, giữ lịch sử, tăng auth_ver và ghi audit cùng transaction. Không cho tự thu hồi
     * hoặc thu hồi vượt phạm vi; kiểm tra còn SUPER_ADMIN cuối cùng sau khi flush.
     */
    @Transactional
    public void revoke(String targetId, Long grantId) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        UserAccount user = access.manageableUser(actorId, targetId);
        if (actorId.equals(targetId)) {
            throw new AccessDeniedException("Self role changes are not allowed");
        }

        RoleAssignment grant = assignments.findById(grantId)
            .filter(g -> g.getUser().getId().equals(targetId) && g.isActive())
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        var boundary = access.boundary(actorId);
        if (!access.visibleGrant(boundary, grant)
            || !access.allowedScopes(boundary, grant.getRole().getCode()).contains(grant.getScopeType())) {
            throw new AccessDeniedException("Grant is outside delegated authority");
        }

        grant.setActive(false);
        assignments.saveAndFlush(grant);
        access.preserveLastSuperAdmin();

        user.setAuthorizationVersion(user.getAuthorizationVersion() + 1);
        users.save(user);

        audit.record(actorId, "USER_ROLE_REVOKED", "USER", targetId, user.getOrgId(),
            Map.of("roleCode", grant.getRole().getCode(), "grantId", grant.getId()));
    }
}
