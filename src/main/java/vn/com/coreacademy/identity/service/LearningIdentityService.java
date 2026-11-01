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

    /** Tên đơn vị theo lô cho báo cáo; giữ cả đơn vị đã ngưng vì dữ liệu lịch sử vẫn tham chiếu. */
    public Map<Long, String> orgNames(Collection<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        organizations.findAllById(ids).forEach(org -> names.put(org.getId(), org.getName()));
        return names;
    }

    /** Tên nhóm theo lô cho báo cáo; giữ cả nhóm đã ngưng vì dữ liệu lịch sử vẫn tham chiếu. */
    public Map<Long, String> teamNames(Collection<Long> ids) {
        Map<Long, String> names = new HashMap<>();
        teams.findAllById(ids).forEach(team -> names.put(team.getId(), team.getName()));
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
        return candidates(kind, userIds, orgIds, teamId, 501);
    }

    /**
     * Học viên đủ điều kiện học (cùng điều kiện với {@link #learners}) trong một nhóm, đơn vị hoặc toàn hệ thống
     * (kind = "all") để xếp hạng. Trả tối đa limit + 1 người để bên gọi phát hiện phạm vi vượt giới hạn.
     */
    public List<Person> rankableLearners(String kind, Collection<Long> orgIds, Long teamId, int limit) {
        return candidates(kind, Set.of(), orgIds, teamId, limit + 1);
    }

    private List<Person> candidates(String kind, Collection<String> userIds, Collection<Long> orgIds, Long teamId,
                                    int size) {
        var ids = userIds.isEmpty() ? Set.of("__no_user__") : userIds;
        var orgs = orgIds.isEmpty() ? Set.of(-1L) : orgIds;
        return users.findLearningCandidates(kind, ids, orgs, teamId, PageRequest.of(0, size)).stream()
            .map(u -> new Person(u.getId(), u.getFullName(), u.getOrgId(), u.getTeamId())).toList();
    }
}
