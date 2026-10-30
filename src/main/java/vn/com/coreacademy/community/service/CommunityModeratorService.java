package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.ModerationRequests.ModeratorRequest;
import vn.com.coreacademy.community.dto.ModerationRequests.ModeratorView;
import vn.com.coreacademy.community.entity.CommunityModerator;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityAccessRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityAccessPolicy;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;

@Service
@RequiredArgsConstructor
public class CommunityModeratorService {
    private final CommunityRepository repository;
    private final CommunityAccessRepository access;
    private final CommunityPolicy policy;
    private final IdentityAccessPolicy administration;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;

    /**
     * ADMIN đúng scope/SUPER_ADMIN xem để cấp hoặc thu hồi; moderator còn hiệu lực xem đơn vị của chính mình để
     * giao case cho người khác. Version trong từng dòng dùng cho PUT tiếp theo.
     */
    @Transactional(readOnly = true)
    public PageResponse<ModeratorView> list(Long orgId, String status, int page, int size) {
        requireDirectory(orgId);
        CommunityPages.check(page, size);
        if (!Set.of(CommunityCodes.ACTIVE, CommunityCodes.REVOKED).contains(status)) {
            throw BusinessException.validation("status", "INVALID_VALUE", "Trạng thái moderator chưa hợp lệ.");
        }
        var grants = access.moderatorPage(orgId, status, page * size, size);
        var names = identity.fullNames(grants.stream()
            .flatMap(grant -> Stream.of(grant.getUserId(), grant.getGrantedBy())).collect(Collectors.toSet()));
        return CommunityPages.of(grants.stream().map(grant -> view(grant, names)).toList(), page, size,
            access.moderatorCount(orgId, status));
    }

    /**
     * Nhân sự của đơn vị để chọn khi bổ nhiệm (ADMIN) hoặc giao người duy trì (moderator); cùng phạm vi như danh
     * sách moderator.
     */
    @Transactional(readOnly = true)
    public PageResponse<PersonRef> candidates(Long orgId, String q, int page, int size) {
        requireDirectory(orgId);
        CommunityPages.check(page, size);
        return CommunityPages.of(access.staffPage(orgId, q, page * size, size).stream()
            .map(row -> new PersonRef(row[0].toString(), row[1].toString())).toList(), page, size,
            access.staffCount(orgId, q));
    }

    @Transactional
    public ModeratorView set(String userId, ModeratorRequest request) {
        writeLock.acquire();
        var actor = policy.actor();
        administration.requireOrg(administration.boundary(actor.person().id()), request.orgId());
        identity.activeOrg(request.orgId());
        if (Objects.equals(userId, actor.person().id())) {
            throw new AccessDeniedException("Self moderator grant is forbidden");
        }
        var person = identity.activePerson(userId);
        if (!Objects.equals(person.orgId(), request.orgId())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        var grants = access.moderators(userId, request.orgId());
        String desired = request.active() ? CommunityCodes.ACTIVE : CommunityCodes.REVOKED;
        CommunityModerator grant = grants.isEmpty() ? new CommunityModerator() : grants.get(0);
        if (!grants.isEmpty() && desired.equals(grant.getStatus())) {
            return view(grant);
        }
        if (grants.isEmpty()) {
            if (request.expectedVersion() != 0) {
                throw new BusinessException(CommunityResponseCode.CONFLICT);
            }
            grant.setUserId(userId);
            grant.setOrgId(request.orgId());
        } else {
            policy.version(grant, request.expectedVersion());
        }
        grant.setStatus(desired);
        grant.setGrantedBy(actor.person().id());
        grant.setGrantedAt(Instant.now());
        grant.setRevokedAt(request.active() ? null : Instant.now());
        repository.save(grant, actor.person().id());
        repository.flush();
        audit.record(actor.person().id(), "COMMUNITY_MODERATOR_CHANGED", "community_moderator",
            grant.getModeratorId().toString(), request.orgId(),
            Map.of("active", request.active(), "reason", request.reason()));
        return view(grant);
    }

    private void requireDirectory(Long orgId) {
        var actor = policy.actor();
        if (!(Objects.equals(orgId, actor.person().orgId()) && policy.isModerator(actor))) {
            administration.requireOrg(administration.boundary(actor.person().id()), orgId);
        }
    }

    private ModeratorView view(CommunityModerator grant) {
        return view(grant, identity.fullNames(List.of(grant.getUserId(), grant.getGrantedBy())));
    }

    private ModeratorView view(CommunityModerator grant, Map<String, String> names) {
        return new ModeratorView(grant.getModeratorId(), grant.getUserId(), grant.getOrgId(), grant.getStatus(),
            grant.getRowVersion(), new PersonRef(grant.getUserId(), names.get(grant.getUserId())),
            new PersonRef(grant.getGrantedBy(), names.get(grant.getGrantedBy())), grant.getGrantedAt(),
                grant.getRevokedAt(),
            grant.getCreateTime(), grant.getUpdateTime());
    }
}
