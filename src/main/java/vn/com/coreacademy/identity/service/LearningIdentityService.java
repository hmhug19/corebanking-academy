package vn.com.coreacademy.identity.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.organization.repository.OrganizationRepository;
import vn.com.coreacademy.organization.repository.TeamRepository;

/** Adapter công khai để Learning và Knowledge sử dụng hồ sơ/scope mà không truy cập repository Identity trực tiếp. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningIdentityService {
    private final IdentityAccessPolicy policy;
    private final AuthorizationService authorization;
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final TeamRepository teams;

    public record Person(String id, String name, Long orgId, Long teamId) {
    }

    public record Actor(Person person, List<RoleGrant> grants) {
    }

    public record TeamInfo(Long id, Long orgId, String name) {
    }

    public Actor actor(String userId) {
        var current = policy.current(userId);
        policy.requirePasswordChanged(current);
        return new Actor(new Person(current.getId(), current.getFullName(), current.getOrgId(), current.getTeamId()),
            authorization.rights(userId).roles());
    }

    public Person activePerson(String id) {
        var user = users.findById(id).filter(u -> u.getStatus() == UserStatus.ACTIVE)
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        return new Person(user.getId(), user.getFullName(), user.getOrgId(), user.getTeamId());
    }

    /** Tên hiển thị theo lô cho một trang kết quả; mã không tồn tại bị bỏ qua. */
    public Map<String, String> fullNames(Collection<String> ids) {
        Map<String, String> names = new HashMap<>();
        users.findAllById(ids).forEach(user -> names.put(user.getId(), user.getFullName()));
        return names;
    }

    public String activeOrg(Long id) {
        policy.requireActiveOrg(id);
        return organizations.findById(id).orElseThrow().getName();
    }

    public Set<Long> activeOrgIds(Long root, boolean descendants) {
        activeOrg(root);
        return descendants ? Set.copyOf(organizations.findActiveDescendantIds(root)) : Set.of(root);
    }

    public Set<Long> activeAncestorIds(Long orgId) {
        if (orgId == null || !organizations.hasActiveAncestry(orgId)) {
            return Set.of();
        }
        return Set.copyOf(organizations.findActiveAncestorIds(orgId));
    }

    public TeamInfo activeTeam(Long id) {
        if (id == null || !teams.existsById(id)) {
            throw new BusinessException(ResponseCode.RESOURCE_NOT_FOUND);
        }
        var team = teams.findById(id).filter(t -> "active".equals(t.getStatus()))
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        activeOrg(team.getOrgId());
        return new TeamInfo(team.getId(), team.getOrgId(), team.getName());
    }

    /** Lọc đủ điều kiện học tại query và trả tối đa 501 để bên gọi phát hiện vượt ngưỡng 500. */
    public List<Person> learners(String kind, Collection<String> userIds, Collection<Long> orgIds, Long teamId) {
        if ("user".equals(kind) && userIds.isEmpty()) {
            return List.of();
        }
        var ids = userIds.isEmpty() ? Set.of("__no_user__") : userIds;
        var orgs = orgIds.isEmpty() ? Set.of(-1L) : orgIds;
        return users.findLearningCandidates(kind, ids, orgs, teamId, PageRequest.of(0, 501)).stream()
            .map(u -> new Person(u.getId(), u.getFullName(), u.getOrgId(), u.getTeamId())).toList();
    }
}
