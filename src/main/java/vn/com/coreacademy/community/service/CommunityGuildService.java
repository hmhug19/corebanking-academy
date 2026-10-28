package vn.com.coreacademy.community.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.constants.CommunityLimits;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.GuildDtos.CreateGuild;
import vn.com.coreacademy.community.dto.GuildDtos.GuildView;
import vn.com.coreacademy.community.dto.GuildDtos.InvitationResponse;
import vn.com.coreacademy.community.dto.GuildDtos.InviteMembers;
import vn.com.coreacademy.community.dto.GuildDtos.MemberAction;
import vn.com.coreacademy.community.dto.GuildDtos.MembershipView;
import vn.com.coreacademy.community.dto.GuildDtos.TransferOwner;
import vn.com.coreacademy.community.dto.GuildDtos.UpdateGuild;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;
import vn.com.coreacademy.community.entity.Guild;
import vn.com.coreacademy.community.entity.GuildMember;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.GuildRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityGuildService {
    private final CommunityRepository repository;
    private final GuildRepository guilds;
    private final CommunityPolicy policy;
    private final CommunityPayload payload;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;

    public PageResponse<GuildView> list(String q, String visibility, boolean mine, int page, int size) {
        var actor = policy.actor();
        CommunityPages.check(page, size);
        query(q);
        var values = guilds.page(actor.person().orgId(), actor.person().id(), q, visibility, mine, page, size);
        var ids = values.stream().map(Guild::getGuildId).toList();
        var metadata = guilds.metadata(ids, actor);
        var memberships = guilds.ownMemberships(ids, actor.person().id());
        var names = identity.fullNames(values.stream().map(Guild::getOwnerUserId).distinct().toList());
        return PageResponse.from(values.map(guild -> view(guild, actor, memberships.get(guild.getGuildId()),
            metadata.get(guild.getGuildId()), names.get(guild.getOwnerUserId()))));
    }

    public GuildView detail(Long id) {
        var actor = policy.actor();
        return view(readable(id, actor), actor);
    }

    @Transactional
    public GuildView create(CreateGuild request) {
        writeLock.acquire();
        var actor = policy.actor();
        String hash = payload.hash("guild", request);
        var existing = guilds.request(actor.person().id(), request.clientRequestId());
        if (!existing.isEmpty()) {
            Guild guild = readable(existing.get(0).getGuildId(), actor);
            if (!hash.equals(guild.getRequestHash())) {
                throw new BusinessException(CommunityResponseCode.IDEMPOTENCY_CONFLICT);
            }
            return view(guild, actor);
        }
        visibility(request.visibility(), request.joinPolicy());
        if (guilds.slugUsed(actor.person().orgId(), request.slug())) {
            throw new BusinessException(CommunityResponseCode.DUPLICATE);
        }
        Guild guild = new Guild();
        guild.setOwnerOrgId(actor.person().orgId());
        guild.setOwnerUserId(actor.person().id());
        guild.setSlug(request.slug());
        guild.setName(request.name().trim());
        guild.setDescription(request.description());
        guild.setVisibility(request.visibility());
        guild.setJoinPolicy(request.joinPolicy());
        guild.setStatus(CommunityCodes.ACTIVE);
        guild.setClientRequestId(request.clientRequestId());
        guild.setRequestHash(hash);
        repository.save(guild, actor.person().id());
        repository.flush();
        GuildMember member = newMember(guild.getGuildId(), actor.person().id());
        member.setMemberRole("owner");
        activate(member, actor.person().id());
        repository.flush();
        log(guild, actor, "CREATED");
        return view(guild, actor);
    }

    @Transactional
    public GuildView update(Long id, UpdateGuild request) {
        writeLock.acquire();
        var actor = policy.actor();
        Guild guild = repository.require(Guild.class, id);
        if (!Objects.equals(guild.getOwnerOrgId(), actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        requireOwnerOrModerator(guild, actor, true);
        policy.version(guild, request.expectedVersion());
        visibility(request.visibility(), request.joinPolicy());
        guild.setName(request.name().trim());
        guild.setDescription(request.description());
        guild.setVisibility(request.visibility());
        guild.setJoinPolicy(request.joinPolicy());
        guild.setStatus(request.status());
        repository.save(guild, actor.person().id());
        log(guild, actor, "UPDATED");
        repository.flush();
        return view(guild, actor);
    }

    @Transactional
    public MembershipView join(Long id) {
        writeLock.acquire();
        var actor = policy.actor();
        Guild guild = readable(id, actor);
        requireWritable(guild);
        GuildMember member = guilds.membership(id, actor.person().id());
        if (member != null && Set.of("active", "pending", "invited").contains(member.getStatus())) {
            return membership(member);
        }
        if (!"organization".equals(guild.getVisibility()) || "invite".equals(guild.getJoinPolicy())) {
            throw new AccessDeniedException("Guild invitation required");
        }
        if (member == null) {
            member = newMember(id, actor.person().id());
        }
        member.setStatus("open".equals(guild.getJoinPolicy()) ? CommunityCodes.ACTIVE : "pending");
        member.setMemberRole("member");
        if (CommunityCodes.ACTIVE.equals(member.getStatus())) {
            member.setJoinedAt(Instant.now());
        }
        member.setLeftAt(null);
        repository.save(member, actor.person().id());
        log(guild, actor, "JOIN_REQUESTED");
        repository.flush();
        return membership(member);
    }

    @Transactional
    public List<MembershipView> invite(Long id, InviteMembers request) {
        writeLock.acquire();
        var actor = policy.actor();
        Guild guild = readable(id, actor);
        requireOwnerOrModerator(guild, actor, false);
        requireWritable(guild);
        Instant expires = request.expiresAt() == null ? Instant.now().plus(Duration.ofDays(7)) : request.expiresAt();
        if (!expires.isAfter(Instant.now()) || expires.isAfter(Instant.now().plus(Duration.ofDays(30)))
            || new HashSet<>(request.userIds()).size() != request.userIds().size()) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        for (String idToInvite : request.userIds()) {
            if (!Objects.equals(identity.activePerson(idToInvite).orgId(), guild.getOwnerOrgId())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        }
        var result = new ArrayList<GuildMember>();
        List<String> newlyInvited = new ArrayList<>();
        for (String userId : request.userIds()) {
            GuildMember member = guilds.membership(id, userId);
            if (member == null || "left".equals(member.getStatus())) {
                member = member == null ? newMember(id, userId) : member;
                member.setMemberRole("member");
                member.setStatus("invited");
                member.setInvitedBy(actor.person().id());
                member.setInvitedAt(Instant.now());
                member.setInvitationExpiresAt(expires);
                repository.save(member, actor.person().id());
                newlyInvited.add(userId);
            }
            // Retry không gửi lại lời mời đã declined; muốn mời lại cần hành động mới tường minh.
            result.add(member);
        }
        repository.flush();
        log(guild, actor, "INVITED");
        if (!newlyInvited.isEmpty()) {
            var data = JsonNodeFactory.instance.objectNode();
            var recipients = data.putArray("recipientUserIds");
            newlyInvited.forEach(recipients::add);
            CommunityOutboxEvent event = new CommunityOutboxEvent();
            event.setOwnerOrgId(guild.getOwnerOrgId());
            event.setGuildId(guild.getGuildId());
            event.setActorUserId(actor.person().id());
            event.setEventType("guild_invited");
            event.setPayloadJson(data);
            event.setDedupKey("guild_invited:" + UUID.randomUUID());
            event.setEventStatus("pending");
            event.setAvailableAt(Instant.now());
            event.setAttemptCount(0);
            repository.save(event, actor.person().id());
        }
        return result.stream().map(this::membership).toList();
    }

    @Transactional
    public MembershipView memberAction(Long id, String userId, MemberAction request) {
        writeLock.acquire();
        var actor = policy.actor();
        Guild guild = readable(id, actor);
        requireOwnerOrModerator(guild, actor, false);
        requireWritable(guild);
        GuildMember member = requireMember(id, userId);
        policy.version(member, request.expectedVersion());
        if ("change_role".equals(request.action()) != (request.memberRole() != null)) {
            throw BusinessException.validation("memberRole", "INVALID_VALUE",
                "Chỉ gửi memberRole khi dùng action change_role.");
        }
        if ("owner".equals(member.getMemberRole()) || Objects.equals(userId, actor.person().id())
            || "moderator".equals(member.getMemberRole()) && !guild.getOwnerUserId().equals(actor.person().id())) {
            throw new AccessDeniedException("Guild owner manages moderators");
        }
        if (!Objects.equals(identity.activePerson(userId).orgId(), guild.getOwnerOrgId())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        switch (request.action()) {
            case "approve", "reject" -> {
                requireStatus(member, "pending");
                if ("approve".equals(request.action())) {
                    activate(member, actor.person().id());
                } else {
                    member.setStatus("rejected");
                }
            }
            case "remove" -> {
                member.setStatus("left");
                member.setLeftAt(Instant.now());
            }
            case "change_role" -> {
                if (!guild.getOwnerUserId().equals(actor.person().id()) || request.memberRole() == null) {
                    throw new AccessDeniedException("Guild owner required");
                }
                requireStatus(member, CommunityCodes.ACTIVE);
                member.setMemberRole(request.memberRole());
            }
            default -> throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        repository.save(member, actor.person().id());
        log(guild, actor, "MEMBER_" + request.action());
        repository.flush();
        return membership(member);
    }

    @Transactional
    public MembershipView invitation(Long id, InvitationResponse request) {
        writeLock.acquire();
        var actor = policy.actor();
        Guild guild = readable(id, actor);
        requireWritable(guild);
        GuildMember member = requireMember(id, actor.person().id());
        policy.version(member, request.expectedVersion());
        requireStatus(member, "invited");
        if (member.getInvitationExpiresAt() == null || !member.getInvitationExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        if ("accept".equals(request.action())) {
            activate(member, actor.person().id());
        } else {
            member.setStatus("rejected");
        }
        repository.save(member, actor.person().id());
        log(guild, actor, "INVITATION_" + request.action());
        repository.flush();
        return membership(member);
    }

    @Transactional
    public MembershipView leave(Long id, Long version) {
        writeLock.acquire();
        var actor = policy.actor();
        Guild guild = readable(id, actor);
        GuildMember member = requireMember(id, actor.person().id());
        if (Objects.equals(guild.getOwnerUserId(), actor.person().id())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        policy.version(member, version);
        member.setStatus("left");
        member.setLeftAt(Instant.now());
        repository.save(member, actor.person().id());
        log(guild, actor, "LEFT");
        repository.flush();
        return membership(member);
    }

    @Transactional
    public GuildView transfer(Long id, TransferOwner request) {
        writeLock.acquire();
        var actor = policy.actor();
        Guild guild = repository.require(Guild.class, id);
        if (!Objects.equals(guild.getOwnerOrgId(), actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        requireOwnerOrModerator(guild, actor, true);
        policy.version(guild, request.expectedVersion());
        GuildMember successor = requireMember(id, request.successorUserId());
        requireStatus(successor, CommunityCodes.ACTIVE);
        if (!Objects.equals(identity.activePerson(successor.getUserId()).orgId(), guild.getOwnerOrgId())
            || Objects.equals(successor.getUserId(), guild.getOwnerUserId())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        GuildMember owner = requireMember(id, guild.getOwnerUserId());
        owner.setMemberRole("member");
        successor.setMemberRole("owner");
        guild.setOwnerUserId(successor.getUserId());
        repository.save(owner, actor.person().id());
        repository.save(successor, actor.person().id());
        repository.save(guild, actor.person().id());
        log(guild, actor, "OWNER_TRANSFERRED");
        repository.flush();
        return view(guild, actor);
    }

    public PageResponse<MembershipView> members(Long id, String q, String status, String role, int page, int size) {
        var actor = policy.actor();
        Guild guild = readable(id, actor);
        if (!policy.member(id, actor)) {
            throw new AccessDeniedException("Guild membership required");
        }
        CommunityPages.check(page, size);
        query(q);
        if (!guild.getOwnerUserId().equals(actor.person().id())) {
            GuildMember caller = requireMember(id, actor.person().id());
            if (!"moderator".equals(caller.getMemberRole())) {
                if (status != null && !CommunityCodes.ACTIVE.equals(status)) {
                    throw new AccessDeniedException("Guild moderator required");
                }
                status = CommunityCodes.ACTIVE;
            }
        }
        return PageResponse.from(guilds.members(id, q, status, role, page, size).map(this::membership));
    }

    public Guild readable(Long id, Actor actor) {
        Guild guild = repository.require(Guild.class, id);
        if (!Objects.equals(guild.getOwnerOrgId(), actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        GuildMember own = guilds.membership(id, actor.person().id());
        boolean invited = own != null && "invited".equals(own.getStatus())
            && own.getInvitationExpiresAt() != null && own.getInvitationExpiresAt().isAfter(Instant.now());
        if ("private".equals(guild.getVisibility()) && !policy.member(id, actor) && !invited) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        return guild;
    }

    public void requireFeed(Long guildId) {
        var actor = policy.actor();
        Guild guild = readable(guildId, actor);
        if ("private".equals(guild.getVisibility()) && !policy.member(guildId, actor)) {
            throw new AccessDeniedException("Active Guild membership required");
        }
    }

    public void requireManager(Long guildId, Actor actor) {
        Guild guild = readable(guildId, actor);
        requireOwnerOrModerator(guild, actor, false);
        requireWritable(guild);
    }

    private GuildView view(Guild guild, Actor actor) {
        GuildMember own = guilds.membership(guild.getGuildId(), actor.person().id());
        return view(guild, actor, own, guilds.metadata(List.of(guild.getGuildId()), actor).get(guild.getGuildId()),
            identity.fullNames(List.of(guild.getOwnerUserId())).get(guild.getOwnerUserId()));
    }

    private GuildView view(Guild guild, Actor actor, GuildMember own, Object[] metadata, String ownerName) {
        boolean unavailable = Boolean.TRUE.equals(metadata[3]);
        boolean readOnly = unavailable || !"active".equals(guild.getStatus());
        boolean manager = !readOnly && own != null && "active".equals(own.getStatus())
            && Set.of("owner", "moderator").contains(own.getMemberRole());
        var membership = own == null ? null : membership(own);
        return new GuildView(guild.getGuildId(), guild.getSlug(), guild.getName(), guild.getDescription(),
            guild.getOwnerUserId(), guild.getVisibility(), guild.getJoinPolicy(), guild.getStatus(),
            guild.getRowVersion(), membership, unavailable, manager, new PersonRef(guild.getOwnerUserId(), ownerName),
            readOnly, membership, ((Number) metadata[1]).longValue(), ((Number) metadata[2]).longValue(),
            Map.of("canManage", manager, "canInvite", manager, "canJoin", !readOnly
                && !"invite".equals(guild.getJoinPolicy())
                && (own == null || Set.of("left", "rejected").contains(own.getStatus())), "canLeave", !readOnly
                    && own != null && "active".equals(own.getStatus()) && !"owner".equals(own.getMemberRole())),
            guild.getCreateTime(), guild.getUpdateTime());
    }

    private boolean ownerAvailable(Guild guild) {
        try {
            return Objects.equals(identity.activePerson(guild.getOwnerUserId()).orgId(), guild.getOwnerOrgId());
        } catch (BusinessException exception) {
            return false;
        }
    }

    private void requireWritable(Guild guild) {
        if (!CommunityCodes.ACTIVE.equals(guild.getStatus()) || !ownerAvailable(guild)) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
    }

    private void requireOwnerOrModerator(Guild guild, Actor actor, boolean ownerOnly) {
        if (guild.getOwnerUserId().equals(actor.person().id()) && ownerAvailable(guild)) {
            return;
        }
        if (ownerOnly) {
            policy.requireModerator(actor);
            return;
        }
        GuildMember member = guilds.membership(guild.getGuildId(), actor.person().id());
        if (member == null || !CommunityCodes.ACTIVE.equals(member.getStatus())
            || !"moderator".equals(member.getMemberRole())) {
            throw new AccessDeniedException("Guild moderator required");
        }
    }

    private void visibility(String visibility, String joinPolicy) {
        if ("private".equals(visibility) && !"invite".equals(joinPolicy)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
    }

    private GuildMember newMember(Long guildId, String userId) {
        GuildMember member = new GuildMember();
        member.setGuildId(guildId);
        member.setUserId(userId);
        member.setMemberRole("member");
        return member;
    }

    private GuildMember requireMember(Long guildId, String userId) {
        GuildMember member = guilds.membership(guildId, userId);
        if (member == null) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        return member;
    }

    private void requireStatus(GuildMember member, String status) {
        if (!status.equals(member.getStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
    }

    private void activate(GuildMember member, String actorId) {
        member.setStatus(CommunityCodes.ACTIVE);
        member.setJoinedAt(Instant.now());
        member.setLeftAt(null);
        member.setApprovedBy(actorId);
        repository.save(member, actorId);
    }

    private MembershipView membership(GuildMember member) {
        boolean own = Objects.equals(member.getUserId(), policy.actor().person().id());
        boolean invited = own && "invited".equals(member.getStatus()) && member.getInvitationExpiresAt() != null
            && member.getInvitationExpiresAt().isAfter(Instant.now());
        return new MembershipView(member.getMembershipId(), member.getGuildId(), member.getUserId(),
            member.getMemberRole(), member.getStatus(), member.getJoinedAt(), member.getInvitationExpiresAt(),
            member.getRowVersion(), new PersonRef(member.getUserId(), identity.fullNames(List.of(member.getUserId()))
                .get(member.getUserId())), member.getLeftAt(), member.getInvitedAt(),
            Map.of("canAccept", invited, "canDecline", invited), member.getCreateTime(), member.getUpdateTime());
    }

    private void query(String q) {
        if (q != null && q.length() > CommunityLimits.QUERY) {
            throw BusinessException.validation("q", "TOO_LONG", "Từ khóa tối đa 300 ký tự.");
        }
    }

    private void log(Guild guild, Actor actor, String action) {
        audit.record(actor.person().id(), "COMMUNITY_GUILD_" + action, "community_guild", guild.getGuildId().toString(),
            guild.getOwnerOrgId(), Map.of("guildId", guild.getGuildId()));
    }
}
