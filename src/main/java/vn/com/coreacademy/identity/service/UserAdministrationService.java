package vn.com.coreacademy.identity.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.common.validation.TextValues;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.identity.dto.CreateUserRequest;
import vn.com.coreacademy.identity.dto.GrantRequest;
import vn.com.coreacademy.identity.dto.ManagedRoleGrant;
import vn.com.coreacademy.identity.dto.Scope;
import vn.com.coreacademy.identity.dto.UserDetail;
import vn.com.coreacademy.identity.dto.UserInfo;
import vn.com.coreacademy.identity.dto.UserSummary;
import vn.com.coreacademy.identity.dto.UserUpdateRequest;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.AuthProvider;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.exception.IdentityResponseCode;
import vn.com.coreacademy.identity.mapper.UserMapper;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.repository.custom.UserRepositoryCustom.UserSearch;
import vn.com.coreacademy.identity.repository.projection.UserSearchRow;
import vn.com.coreacademy.identity.security.PasswordPolicy;
import vn.com.coreacademy.identity.service.IdentityAccessPolicy.Boundary;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.entity.Team;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.entity.Team;
import vn.com.coreacademy.organization.repository.OrganizationRepository;
import vn.com.coreacademy.organization.repository.TeamRepository;
import vn.com.coreacademy.security.SecurityContextUtils;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserAdministrationService {
    private final UserRepository users;
    private final RoleAssignmentRepository assignments;
    private final OrganizationRepository organizations;
    private final TeamRepository teams;
    private final IdentityAccessPolicy access;
    private final IdentityWriteLock writeLock;
    private final RoleGrantService grants;
    private final PasswordEncoder passwords;
    private final UserMapper mapper;
    private final IdentityAuditService audit;

    /**
     * Tra cứu người dùng với phạm vi quản lý được lọc tại database trước phân trang và tính tổng. Bộ lọc role
     * chỉ xét phân quyền người gọi được phép thấy; nhãn tổ chức, nhóm và vai trò được tải theo lô cho trang
     * kết quả.
     */
    public PageResponse<UserSummary> list(String q, String roleCode, Long orgId, Long teamId, String status, int page,
                                          int size, String sort) {
        String actorId = SecurityContextUtils.getUserId();
        var boundary = access.boundary(actorId);
        var filter = buildSearchFilter(boundary, q, roleCode, orgId, teamId, status);

        var result = users.searchUsers(filter,
            PageQuery.of(page, size, sort, Set.of("id", "username", "fullName", "createTime")));

        var context = loadSummaryContext(result.getContent(), boundary);
        return PageResponse.from(result.map(user -> toSummary(user, boundary, context)));
    }

    private UserSearch buildSearchFilter(Boundary boundary, String q, String roleCode, Long orgId, Long teamId,
                                         String status) {
        Set<Long> selectedOrgIds = null;
        if (orgId != null) {
            access.requireOrg(boundary, orgId);
            selectedOrgIds = Set.copyOf(organizations.findActiveDescendantIds(orgId));
        }

        if (teamId != null) {
            var team = teams.findById(teamId)
                .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
            access.requireOrg(boundary, team.getOrgId());
        }

        return new UserSearch(q, roleCode, selectedOrgIds, teamId, status == null ? null : parseStatus(status),
            boundary.global(), boundary.orgIds());
    }

    private UserSummaryContext loadSummaryContext(List<UserSearchRow> rows, Boundary boundary) {
        Set<Long> orgIds = rows.stream().map(UserSearchRow::orgId).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Set<Long> teamIds = rows.stream().map(UserSearchRow::teamId).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        var orgLabels = organizations.findAllById(orgIds).stream()
            .collect(Collectors.toMap(o -> o.getId(), Function.identity()));
        var teamLabels = teams.findAllById(teamIds).stream()
            .collect(Collectors.toMap(t -> t.getId(), Function.identity()));

        var roleGrants = rows.isEmpty() ? Map.<String, List<RoleAssignment>>of()
            : assignments.findActiveForUsers(rows.stream().map(UserSearchRow::id).toList()).stream()
            .collect(Collectors.groupingBy(g -> g.getUser().getId()));
        var visibleGrantIds = access.visibleGrantIds(boundary,
            roleGrants.values().stream().flatMap(List::stream).toList());

        return new UserSummaryContext(orgLabels, teamLabels, roleGrants, visibleGrantIds);
    }

    private UserSummary toSummary(UserSearchRow user, Boundary boundary, UserSummaryContext context) {
        var team = context.teams().get(user.teamId());
        if (team != null && !Objects.equals(team.getOrgId(), user.orgId())) {
            team = null;
        }

        List<RoleAssignment> userGrants = context.roleGrants().getOrDefault(user.id(), List.of());
        List<String> roleCodes = userGrants.stream().filter(g -> context.visibleGrantIds().contains(g.getId()))
            .map(g -> g.getRole().getCode()).distinct().sorted().toList();
        boolean editable = "LOCAL".equals(user.authProvider()) && access.canManage(boundary, userGrants);

        return mapper.toSummary(user, context.organizations().get(user.orgId()), team, roleCodes, editable);
    }

    private record UserSummaryContext(Map<Long, Organization> organizations, Map<Long, Team> teams,
                                      Map<String, List<RoleAssignment>> roleGrants, Set<Long> visibleGrantIds) {
    }

    /**
     * Trả hồ sơ và các phân quyền trong phạm vi được phép xem. editable/revocable chỉ là gợi ý giao diện; mọi
     * thao tác ghi vẫn phải qua policy.
     */
    public UserDetail get(String targetId) {
        String actorId = SecurityContextUtils.getUserId();
        UserAccount target = access.visibleUser(actorId, targetId);
        var boundary = access.boundary(actorId);
        List<RoleAssignment> userGrants = assignments.findActiveForUser(targetId);
        boolean manageable = access.canManage(boundary, userGrants);
        List<ManagedRoleGrant> visible = userGrants.stream().filter(g -> access.visibleGrant(boundary, g))
            .map(g -> grantInfo(actorId, g, boundary, manageable)).toList();
        boolean editable = target.getAuthProvider() == AuthProvider.LOCAL && manageable;
        return new UserDetail(info(target), visible, editable);
    }

    /**
     * Tạo tài khoản LOCAL cùng tổ chức, nhóm, phân quyền ban đầu và audit trong một transaction.
     *
     * <p>Kiểm tra trùng chéo mã cán bộ/tên đăng nhập và bắt buộc đổi mật khẩu tạm. Không tự cấp vai trò mặc
     * định; một phân quyền ban đầu không hợp lệ sẽ hủy toàn bộ yêu cầu tạo.
     */
    @Transactional
    public UserDetail create(CreateUserRequest request) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        var boundary = access.boundary(actorId);

        String username = request.username().toLowerCase(Locale.ROOT);
        if (users.identifierExists(request.userId()) || users.identifierExists(username)) {
            throw new BusinessException(IdentityResponseCode.DUPLICATE_IDENTIFIER);
        }

        access.requireOrg(boundary, request.orgId());
        access.validateMembership(request.orgId(), request.teamId());
        PasswordPolicy.validate(request.initialPassword(), "initialPassword");

        UserAccount user = mapper.toNewAccount(request);
        user.setUsername(username);
        user.setFullName(TextValues.required(request.fullName(), "fullName"));
        user.setPasswordHash(passwords.encode(request.initialPassword()));
        user.setMustChangePassword(true);
        users.save(user);

        if (request.initialRoleGrants() != null) {
            for (GrantRequest grant : request.initialRoleGrants()) {
                grants.addWithinTransaction(actorId, user, grant);
            }
        }

        audit.record(actorId, "USER_CREATED", "USER", user.getId(), user.getOrgId(),
            Map.of("username", username, "temporaryPassword", true));
        return get(user.getId());
    }

    /**
     * Cập nhật hồ sơ LOCAL và tổ chức/nhóm trong phạm vi quản lý của người gọi.
     *
     * <p>Khi tổ chức hoặc nhóm thay đổi, kiểm tra cả nguồn và đích, thu hồi mọi phân quyền khác OWN và tăng
     * auth_ver. Hồ sơ, phân quyền và audit cùng transaction; không cho tự chuyển tổ chức hoặc nhóm.
     */
    @Transactional
    public UserDetail update(String targetId, UserUpdateRequest request) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        UserAccount user = access.manageableUser(actorId, targetId);
        if (user.getAuthProvider() != AuthProvider.LOCAL) {
            throw new AccessDeniedException("SSO profile is managed by the identity provider");
        }
        if (request.getSuppliedFields().isEmpty()) {
            throw BusinessException.validation("", "EMPTY_UPDATE", "Vui lòng cung cấp thông tin cần cập nhật.");
        }

        Long orgId = request.has("orgId") ? request.getOrgId() : user.getOrgId();
        Long teamId = request.has("teamId") ? request.getTeamId() : user.getTeamId();
        boolean moved = !Objects.equals(orgId, user.getOrgId()) || !Objects.equals(teamId, user.getTeamId());
        if (moved) {
            revokeScopedGrantsForTransfer(actorId, targetId, orgId, teamId);
        }

        Long previousOrgId = user.getOrgId();
        applyProfileChanges(user, request);
        user.setOrgId(orgId);
        user.setTeamId(teamId);
        if (moved) {
            user.setAuthorizationVersion(user.getAuthorizationVersion() + 1);
        }

        users.saveAndFlush(user);
        if (moved) {
            access.preserveLastSuperAdmin();
        }

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("fields", request.getSuppliedFields());
        changes.put("previousOrgId", previousOrgId);
        changes.put("teamId", teamId);
        changes.put("scopedGrantsRevoked", moved);
        audit.record(actorId, moved ? "USER_MEMBERSHIP_CHANGED" : "USER_PROFILE_UPDATED", "USER",
            targetId, orgId, changes);

        return get(targetId);
    }

    /** Kiểm tra phạm vi nguồn/đích trước khi thu hồi grant; không chuyển quyền cũ sang tổ chức mới. */
    private void revokeScopedGrantsForTransfer(String actorId, String targetId, Long orgId, Long teamId) {
        if (actorId.equals(targetId)) {
            throw new AccessDeniedException("Use another administrator to change your membership");
        }
        access.requireOrg(access.boundary(actorId), orgId);
        access.validateMembership(orgId, teamId);
        // Không mang quyền được ủy nhiệm sang tổ chức/nhóm mới; kiểm tra nguồn trước khi thu hồi.
        List<RoleAssignment> oldGrants = assignments.findActiveForUser(targetId);
        if (!access.boundary(actorId).global() && oldGrants.stream()
            .anyMatch(g -> !access.visibleGrant(access.boundary(actorId), g))) {
            throw new AccessDeniedException("Transfer requires a global administrator");
        }
        for (RoleAssignment grant : oldGrants) {
            if (grant.getScopeType() != ScopeType.OWN) {
                grant.setActive(false);
                assignments.save(grant);
            }
        }
    }

    private void applyProfileChanges(UserAccount user, UserUpdateRequest request) {
        if (request.has("fullName")) {
            user.setFullName(TextValues.required(request.getFullName(), "fullName"));
        }
        if (request.has("email")) {
            user.setEmail(request.getEmail());
        }
        if (request.has("positionName")) {
            user.setPositionName(request.getPositionName());
        }
    }

    /**
     * Đổi trạng thái tài khoản, thu hồi token và ghi audit khi trạng thái thực sự thay đổi. Không cho tự tạm
     * dừng/khóa hoặc làm mất SUPER_ADMIN cuối cùng; các thay đổi cùng transaction.
     */
    @Transactional
    public UserDetail status(String targetId, String status) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        UserAccount user = access.manageableUser(actorId, targetId);

        UserStatus next = parseStatus(status);
        if (actorId.equals(targetId) && next != UserStatus.ACTIVE) {
            throw new AccessDeniedException("Self suspension is not allowed");
        }

        if (next != user.getStatus()) {
            UserStatus previous = user.getStatus();
            user.setStatus(next);
            user.setAuthorizationVersion(user.getAuthorizationVersion() + 1);
            users.saveAndFlush(user);
            access.preserveLastSuperAdmin();
            audit.record(actorId, "USER_STATUS_CHANGED", "USER", targetId, user.getOrgId(),
                Map.of("before", previous.getValue(), "after", next.getValue()));
        }

        return get(targetId);
    }

    public UserInfo info(UserAccount user) {
        var org = user.getOrgId() == null ? null : organizations.findById(user.getOrgId()).orElse(null);
        var team = user.getTeamId() == null ? null : teams.findById(user.getTeamId()).orElse(null);
        if (team != null && !Objects.equals(team.getOrgId(), user.getOrgId())) {
            team = null;
        }

        return mapper.toInfo(user, org, team);
    }

    private ManagedRoleGrant grantInfo(String actorId, RoleAssignment grant, IdentityAccessPolicy.Boundary boundary,
                                       boolean manageable) {
        var org = grant.getOrgId() == null ? null : organizations.findById(grant.getOrgId()).orElse(null);
        var team = grant.getTeamId() == null ? null : teams.findById(grant.getTeamId()).orElse(null);

        boolean revocable = manageable && !actorId.equals(grant.getUser().getId())
            && access.allowedScopes(boundary, grant.getRole().getCode())
            .contains(grant.getScopeType());

        return new ManagedRoleGrant(grant.getId(), grant.getRole().getId(), grant.getRole().getCode(),
            grant.getRole().getName(), new Scope(grant.getScopeType(), grant.getOrgId(), grant.getTeamId()),
            org == null ? null : org.getName(), team == null ? null : team.getName(),
            grant.getGrantedBy(), grant.getCreateTime(), revocable);
    }

    public static UserStatus parseStatus(String value) {
        for (UserStatus status : UserStatus.values()) {
            if (status.getValue().equals(value)) {
                return status;
            }
        }
        throw BusinessException.validation("status", "INVALID_VALUE", "Trạng thái tài khoản không hợp lệ.");
    }
}
