package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
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
        String desired = request.active() ? CommunityCodes.ACTIVE : "revoked";
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

    private ModeratorView view(CommunityModerator grant) {
        var names = identity.fullNames(List.of(grant.getUserId(), grant.getGrantedBy()));
        return new ModeratorView(grant.getModeratorId(), grant.getUserId(), grant.getOrgId(), grant.getStatus(),
            grant.getRowVersion(), new PersonRef(grant.getUserId(), names.get(grant.getUserId())),
            new PersonRef(grant.getGrantedBy(), names.get(grant.getGrantedBy())), grant.getGrantedAt(),
                grant.getRevokedAt(),
            grant.getCreateTime(), grant.getUpdateTime());
    }
}
