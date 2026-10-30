package vn.com.coreacademy.identity.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.identity.dto.FunctionInfo;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.dto.Scope;
import vn.com.coreacademy.identity.dto.UserRights;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.repository.FunctionRepository;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.organization.repository.OrganizationRepository;
import vn.com.coreacademy.organization.repository.TeamRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthorizationService {
    private final RoleAssignmentRepository assignments;
    private final FunctionRepository functions;
    private final TeamRepository teams;
    private final OrganizationRepository organizations;
    private final IdentityAccessPolicy access;
    private final List<FunctionGrantSource> grantSources;

    /**
     * Tổng hợp các vai trò, chức năng và scope còn hiệu lực từ database, cộng chức năng do module khác cấp qua
     * FunctionGrantSource. Tài khoản phải đổi mật khẩu tạm nhận quyền rỗng; chức năng chỉ được gộp để hiển thị,
     * không thay kiểm tra quyền hành động.
     */
    public UserRights rights(String userId) {
        var current = access.current(userId);
        if (current.isMustChangePassword()) {
            return new UserRights(List.of(), List.of(), Map.of());
        }

        List<RoleGrant> roles = new ArrayList<>();
        Map<Long, FunctionInfo> screens = new LinkedHashMap<>();
        Map<String, List<Scope>> scoped = new TreeMap<>();

        for (RoleAssignment grant : assignments.findActiveForUser(userId)) {
            if (!effective(grant)) {
                continue;
            }

            Scope scope = new Scope(grant.getScopeType(), grant.getOrgId(), grant.getTeamId());
            roles.add(new RoleGrant(grant.getRole().getCode(), grant.getRole().getName(), scope));
            for (var f : functions.findActiveForRole(grant.getRole().getId())) {
                screens.putIfAbsent(f.getId(), new FunctionInfo(f.getId(), f.getCode(), f.getName(),
                    f.getRoutePattern(), f.getParentFunctionId(), f.getSortNo()));
                scoped.computeIfAbsent(f.getCode(), key -> new ArrayList<>()).add(scope);
            }
        }

        if (current.getOrgId() != null) {
            for (var source : grantSources) {
                for (var grant : source.grants(userId, current.getOrgId())) {
                    // Chức năng đã có từ vai trò thì chỉ thêm scope (không lặp), khỏi tra lại bảng chức năng.
                    boolean present = screens.values().stream()
                        .anyMatch(f -> f.code().equals(grant.functionCode()))
                        || functions.findByCodeAndActiveTrue(grant.functionCode()).map(f -> {
                            screens.putIfAbsent(f.getId(), new FunctionInfo(f.getId(), f.getCode(), f.getName(),
                                f.getRoutePattern(), f.getParentFunctionId(), f.getSortNo()));
                            return true;
                        }).orElse(false);
                    if (present) {
                        List<Scope> scopes = scoped.computeIfAbsent(grant.functionCode(), key -> new ArrayList<>());
                        if (!scopes.contains(grant.scope())) {
                            scopes.add(grant.scope());
                        }
                    }
                }
            }
        }

        List<FunctionInfo> ordered = screens.values().stream()
            .sorted(Comparator.comparingInt(FunctionInfo::sortNo).thenComparing(FunctionInfo::id)).toList();
        return new UserRights(roles, ordered, scoped);
    }

    /**
     * Yêu cầu đúng vai trò và scope phù hợp trên cùng một phân quyền còn hiệu lực. Không ghép vai trò của
     * grant này với scope grant khác, không tự bỏ qua kiểm tra cho SUPER_ADMIN.
     *
     * <p>orgId/teamId/ownerId phải lấy từ tài nguyên tin cậy ở backend, không dùng trực tiếp dữ liệu client để
     * chứng minh quyền.
     */
    public void requireRole(String userId, String role, Long orgId, Long teamId, String ownerId) {
        access.requirePasswordChanged(access.current(userId));

        for (RoleAssignment grant : assignments.findActiveForUser(userId)) {
            if (!grant.getRole().getCode().equals(role) || !effective(grant)) {
                continue;
            }
            if (matchesScope(grant, userId, orgId, teamId, ownerId)) {
                return;
            }
        }

        throw new AccessDeniedException("Role or scope denied");
    }

    private boolean matchesScope(RoleAssignment grant, String userId, Long orgId, Long teamId, String ownerId) {
        ScopeType scope = grant.getScopeType();
        if (scope == ScopeType.GLOBAL && grant.getOrgId() == null && grant.getTeamId() == null) {
            return true;
        }
        if (scope == ScopeType.ORG && orgId != null
            && organizations.findActiveDescendantIds(grant.getOrgId()).contains(orgId)) {
            return true;
        }
        if (scope == ScopeType.TEAM && teamId != null && teamId.equals(grant.getTeamId()) && orgId != null) {
            var team = teams.findById(teamId).orElse(null);
            if (team != null && "active".equals(team.getStatus()) && orgId.equals(team.getOrgId())
                && (grant.getOrgId() == null || orgId.equals(grant.getOrgId()))) {
                return true;
            }
        }
        if (scope == ScopeType.OWN && grant.getOrgId() == null && grant.getTeamId() == null
            && userId.equals(ownerId)) {
            return true;
        }
        return false;
    }

    /**
     * Loại phân quyền sai cấu trúc hoặc có đích không còn hiệu lực. TEAM được kiểm tra theo tổ chức thực tế;
     * ORG yêu cầu toàn bộ đường cha đang hoạt động.
     */
    private boolean effective(RoleAssignment grant) {
        return switch (grant.getScopeType()) {
            case GLOBAL, OWN -> grant.getOrgId() == null && grant.getTeamId() == null;
            case ORG -> grant.getOrgId() != null && grant.getTeamId() == null
                && organizations.hasActiveAncestry(grant.getOrgId());
            case TEAM -> grant.getTeamId() != null && teams.findById(grant.getTeamId())
                .filter(t -> "active".equals(t.getStatus()))
                .filter(t -> grant.getOrgId() == null || grant.getOrgId().equals(t.getOrgId()))
                .filter(t -> organizations.hasActiveAncestry(t.getOrgId())).isPresent();
        };
    }
}
