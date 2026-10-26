package vn.com.coreacademy.identity.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.dto.GrantRequest;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.exception.IdentityResponseCode;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.organization.repository.OrganizationRepository;
import vn.com.coreacademy.organization.repository.TeamRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class IdentityAccessPolicy {
    private final UserRepository users;
    private final RoleAssignmentRepository assignments;
    private final OrganizationRepository organizations;
    private final TeamRepository teams;

    public record Boundary(boolean global, boolean superAdmin, Set<Long> orgIds) {
        public boolean contains(Long orgId) {
            return global || (orgId != null && orgIds.contains(orgId));
        }
    }

    public UserAccount current(String actorId) {
        UserAccount actor = users.findById(actorId)
            .orElseThrow(() -> new BusinessException(ResponseCode.UNAUTHORIZED));
        if (actor.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ResponseCode.UNAUTHORIZED);
        }
        return actor;
    }

    public void requirePasswordChanged(UserAccount actor) {
        if (actor.isMustChangePassword()) {
            throw new BusinessException(IdentityResponseCode.PASSWORD_CHANGE_REQUIRED);
        }
    }

    /**
     * Tính phạm vi quản trị từ các phân quyền đang hoạt động trong database, không từ quyền màn hình hoặc
     * claim roles của JWT.
     *
     * <p>ADMIN/SUPER_ADMIN GLOBAL hợp lệ cấp phạm vi toàn hệ thống; ADMIN ORG gồm node và cây con active khi
     * toàn bộ đường cha hợp lệ. Chặn tài khoản chưa đổi mật khẩu tạm hoặc không có quyền quản trị.
     */
    public Boundary boundary(String actorId) {
        requirePasswordChanged(current(actorId));

        Set<Long> orgIds = new HashSet<>();
        boolean global = false;

        for (RoleAssignment grant : assignments.findActiveForUser(actorId)) {
            String role = grant.getRole().getCode();
            if (!Set.of(RoleCodes.ADMIN, RoleCodes.SUPER_ADMIN).contains(role)) {
                continue;
            }
            if (isGlobal(grant)) {
                if (RoleCodes.SUPER_ADMIN.equals(role)) {
                    return new Boundary(true, true, Set.of());
                }
                global = true;
            }
            if (RoleCodes.ADMIN.equals(role) && grant.getScopeType() == ScopeType.ORG
                && grant.getOrgId() != null && grant.getTeamId() == null
                && organizations.hasActiveAncestry(grant.getOrgId())) {
                orgIds.addAll(organizations.findActiveDescendantIds(grant.getOrgId()));
            }
        }

        if (!global && orgIds.isEmpty()) {
            throw denied();
        }

        return new Boundary(global, false, global ? Set.of() : Set.copyOf(orgIds));
    }

    public void requireGlobal(String actorId) {
        if (!boundary(actorId).global()) {
            throw denied();
        }
    }

    public void requireOrg(Boundary boundary, Long orgId) {
        if (!boundary.contains(orgId)) {
            throw denied();
        }
    }

    public UserAccount visibleUser(String actorId, String targetId) {
        Boundary boundary = boundary(actorId);
        UserAccount target = users.findById(targetId)
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        // Trả 404 để không tiết lộ tài khoản ngoài phạm vi, kể cả khi truy cập URL trực tiếp.
        if (!boundary.contains(target.getOrgId())) {
            throw new BusinessException(ResponseCode.RESOURCE_NOT_FOUND);
        }
        return target;
    }

    /**
     * Kiểm tra tài khoản thuộc phạm vi được xem trước khi cho phép quản lý. Chỉ SUPER_ADMIN được quản lý
     * tài khoản có vai trò ADMIN/SUPER_ADMIN; ADMIN vẫn tự sửa hồ sơ cá nhân qua SelfProfileService.
     */
    public UserAccount manageableUser(String actorId, String targetId) {
        UserAccount target = visibleUser(actorId, targetId);
        Boundary boundary = boundary(actorId);
        if (!canManage(boundary, assignments.findActiveForUser(targetId))) {
            throw denied();
        }
        return target;
    }

    /**
     * Bảo vệ tài khoản SUPER_ADMIN trước mọi ADMIN, kể cả ADMIN GLOBAL.
     * Chỉ SUPER_ADMIN quản lý tài khoản có vai trò quản trị, tránh thu hồi ADMIN gián tiếp qua chuyển nhóm.
     */
    public boolean canManage(Boundary boundary, List<RoleAssignment> targetGrants) {
        if (boundary.superAdmin()) {
            return true;
        }
        return targetGrants.stream().noneMatch(g ->
            Set.of(RoleCodes.ADMIN, RoleCodes.SUPER_ADMIN).contains(g.getRole().getCode()));
    }

    /**
     * Giới hạn phân quyền được hiển thị theo boundary của người gọi. Với TEAM, đối chiếu tổ chức thực tế của
     * nhóm thay vì tin riêng orgId lưu trên phân quyền.
     */
    public boolean visibleGrant(Boundary boundary, RoleAssignment grant) {
        if (boundary.global()) {
            return true;
        }

        Long teamOrgId = grant.getScopeType() == ScopeType.TEAM && grant.getTeamId() != null
            ? teams.findById(grant.getTeamId()).map(t -> t.getOrgId()).orElse(null) : null;
        return visibleGrant(boundary, grant, teamOrgId);
    }

    /** Tải tổ chức của các nhóm trong một lượt để dựng roleCodes cho cả trang, tránh query theo từng grant. */
    public Set<Long> visibleGrantIds(Boundary boundary, Collection<RoleAssignment> grants) {
        Map<Long, Long> teamOrgs = Map.of();
        if (!boundary.global()) {
            Set<Long> teamIds = grants.stream().filter(g -> g.getScopeType() == ScopeType.TEAM)
                .map(RoleAssignment::getTeamId).filter(Objects::nonNull).collect(Collectors.toSet());
            if (!teamIds.isEmpty()) {
                teamOrgs = teams.findAllById(teamIds).stream()
                    .collect(Collectors.toMap(t -> t.getId(), t -> t.getOrgId()));
            }
        }
        Map<Long, Long> actualTeamOrgs = teamOrgs;
        return grants.stream().filter(g -> visibleGrant(boundary, g,
                g.getTeamId() == null ? null : actualTeamOrgs.get(g.getTeamId())))
            .map(RoleAssignment::getId).collect(Collectors.toSet());
    }

    private boolean visibleGrant(Boundary boundary, RoleAssignment grant, Long teamOrgId) {
        if (boundary.global()) {
            return true;
        }

        return switch (grant.getScopeType()) {
            case OWN -> grant.getOrgId() == null && grant.getTeamId() == null;
            case ORG -> grant.getTeamId() == null && boundary.contains(grant.getOrgId());
            case TEAM -> teamOrgId != null && boundary.contains(teamOrgId)
                && (grant.getOrgId() == null || Objects.equals(grant.getOrgId(), teamOrgId));
            case GLOBAL -> false;
        };
    }

    public List<ScopeType> allowedScopes(Boundary boundary, String role) {
        return switch (role) {
            case RoleCodes.LEARNER -> List.of(ScopeType.OWN);
            case RoleCodes.INSTRUCTOR -> boundary.global()
                ? List.of(ScopeType.OWN, ScopeType.TEAM, ScopeType.ORG, ScopeType.GLOBAL)
                : List.of(ScopeType.OWN, ScopeType.TEAM, ScopeType.ORG);
            case RoleCodes.REPORT_VIEWER -> boundary.global()
                ? List.of(ScopeType.TEAM, ScopeType.ORG, ScopeType.GLOBAL)
                : List.of(ScopeType.TEAM, ScopeType.ORG);
            case RoleCodes.ADMIN -> boundary.superAdmin() ? List.of(ScopeType.ORG, ScopeType.GLOBAL) : List.of();
            case RoleCodes.SUPER_ADMIN -> boundary.superAdmin() ? List.of(ScopeType.GLOBAL) : List.of();
            default -> List.of();
        };
    }

    /**
     * Kiểm tra cặp vai trò-scope được phép cấp và tính hợp lệ của đích tổ chức/nhóm. GLOBAL/OWN không có đích;
     * ORG/TEAM phải thuộc boundary và tổ chức có đường cha đang hoạt động.
     */
    public void requireGrant(String actorId, String role, GrantRequest request) {
        Boundary boundary = boundary(actorId);
        if (!allowedScopes(boundary, role).contains(request.scope())) {
            throw denied();
        }

        switch (request.scope()) {
            case GLOBAL, OWN -> {
                if (request.orgId() != null) {
                    throw BusinessException.field(IdentityResponseCode.INVALID_SCOPE, "orgId", "NOT_ALLOWED",
                        "Phạm vi GLOBAL hoặc OWN không được chọn tổ chức.");
                }
                if (request.teamId() != null) {
                    throw BusinessException.field(IdentityResponseCode.INVALID_SCOPE, "teamId", "NOT_ALLOWED",
                        "Phạm vi GLOBAL hoặc OWN không được chọn nhóm.");
                }
            }
            case ORG -> {
                if (request.orgId() == null) {
                    throw BusinessException.field(IdentityResponseCode.INVALID_SCOPE, "orgId", "REQUIRED",
                        "Vui lòng chọn tổ chức cho phạm vi ORG.");
                }
                if (request.teamId() != null) {
                    throw BusinessException.field(IdentityResponseCode.INVALID_SCOPE, "teamId", "NOT_ALLOWED",
                        "Phạm vi ORG không được chọn nhóm.");
                }
                requireOrg(boundary, request.orgId());
                requireActiveOrg(request.orgId());
            }
            case TEAM -> {
                if (request.teamId() == null) {
                    throw BusinessException.field(IdentityResponseCode.INVALID_SCOPE, "teamId", "REQUIRED",
                        "Vui lòng chọn nhóm cho phạm vi TEAM.");
                }
                var team = teams.findById(request.teamId()).orElse(null);
                if (team == null) {
                    throw BusinessException.field(IdentityResponseCode.INVALID_SCOPE, "teamId", "NOT_FOUND",
                        "Nhóm được chọn không còn tồn tại. Vui lòng chọn lại nhóm.");
                }
                if (!"active".equals(team.getStatus())) {
                    throw BusinessException.field(IdentityResponseCode.INVALID_SCOPE, "teamId", "INACTIVE",
                        "Nhóm được chọn đã ngừng hoạt động. Vui lòng chọn nhóm khác.");
                }
                if (request.orgId() != null && !Objects.equals(request.orgId(), team.getOrgId())) {
                    throw BusinessException.field(IdentityResponseCode.INVALID_SCOPE, "teamId", "MISMATCH",
                        "Nhóm không thuộc tổ chức đã chọn. Vui lòng chọn lại nhóm.");
                }
                requireOrg(boundary, team.getOrgId());
                requireActiveOrg(team.getOrgId(), request.orgId() == null ? "teamId" : "orgId");
            }
        }
    }

    public void requireActiveOrg(Long orgId) {
        requireActiveOrg(orgId, "orgId");
    }

    /** Field phụ thuộc ngữ cảnh: tổ chức hồ sơ, tổ chức cha hoặc tổ chức của nhóm được chọn. */
    public void requireActiveOrg(Long orgId, String field) {
        if (orgId == null) {
            throw BusinessException.field(IdentityResponseCode.INVALID_MEMBERSHIP, field, "REQUIRED",
                "Vui lòng chọn tổ chức.");
        }
        if (!organizations.hasActiveAncestry(orgId)) {
            if (!organizations.existsById(orgId)) {
                throw BusinessException.field(IdentityResponseCode.INVALID_MEMBERSHIP, field, "NOT_FOUND",
                    "Tổ chức được chọn không còn tồn tại. Vui lòng chọn lại.");
            }
            throw BusinessException.field(IdentityResponseCode.INVALID_MEMBERSHIP, field, "INVALID_VALUE",
                "Tổ chức được chọn hoặc cấp cha đã ngừng hoạt động, hoặc cây tổ chức không hợp lệ.");
        }
    }

    /**
     * Kiểm tra quan hệ logic khi gán tổ chức/nhóm vì schema không dùng khóa ngoại vật lý. Nhóm phải đang hoạt
     * động và thuộc đúng orgId; tổ chức phải có đường cha active hợp lệ.
     */
    public void validateMembership(Long orgId, Long teamId) {
        if (orgId != null) {
            requireActiveOrg(orgId);
        }

        if (teamId != null) {
            if (orgId == null) {
                throw BusinessException.field(IdentityResponseCode.INVALID_MEMBERSHIP, "orgId", "REQUIRED",
                    "Vui lòng chọn tổ chức trước khi gán nhóm.");
            }
            var team = teams.findById(teamId).orElse(null);
            if (team == null) {
                throw BusinessException.field(IdentityResponseCode.INVALID_MEMBERSHIP, "teamId", "NOT_FOUND",
                    "Nhóm được chọn không còn tồn tại. Vui lòng chọn lại nhóm.");
            }
            if (!"active".equals(team.getStatus())) {
                throw BusinessException.field(IdentityResponseCode.INVALID_MEMBERSHIP, "teamId", "INACTIVE",
                    "Nhóm được chọn đã ngừng hoạt động. Vui lòng chọn nhóm khác.");
            }
            if (!Objects.equals(orgId, team.getOrgId())) {
                throw BusinessException.field(IdentityResponseCode.INVALID_MEMBERSHIP, "teamId", "MISMATCH",
                    "Nhóm không thuộc tổ chức đã chọn. Vui lòng chọn lại nhóm.");
            }
        }
    }

    /**
     * Từ chối thay đổi nếu không còn SUPER_ADMIN GLOBAL đang hoạt động. Bên gọi phải flush thay đổi và giữ
     * IdentityWriteLock trong cùng transaction để kiểm tra có ý nghĩa khi nhiều instance ghi đồng thời.
     */
    public void preserveLastSuperAdmin() {
        if (assignments.findEffectiveSuperAdministrators().isEmpty()) {
            throw new BusinessException(IdentityResponseCode.LAST_SUPER_ADMIN);
        }
    }

    public boolean isGlobal(RoleAssignment grant) {
        return grant.getScopeType() == ScopeType.GLOBAL && grant.getOrgId() == null && grant.getTeamId() == null;
    }

    private AccessDeniedException denied() {
        return new AccessDeniedException("Administrative action or data scope denied");
    }
}
