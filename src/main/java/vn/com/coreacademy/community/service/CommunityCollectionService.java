package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.CollectionDtos.BookmarkView;
import vn.com.coreacademy.community.dto.CollectionDtos.CollectionCapabilities;
import vn.com.coreacademy.community.dto.CollectionDtos.CollectionItemView;
import vn.com.coreacademy.community.dto.CollectionDtos.CollectionItems;
import vn.com.coreacademy.community.dto.CollectionDtos.CollectionView;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;
import vn.com.coreacademy.community.dto.CollectionDtos.CreateCollection;
import vn.com.coreacademy.community.dto.CollectionDtos.ItemResult;
import vn.com.coreacademy.community.dto.CollectionDtos.SaveItem;
import vn.com.coreacademy.community.dto.CollectionDtos.UpdateCollection;
import vn.com.coreacademy.community.dto.ContentRequests.GrantInput;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.GrantView;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.entity.CollectionAccessGrant;
import vn.com.coreacademy.community.entity.CollectionItem;
import vn.com.coreacademy.community.entity.CommunityCollection;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityCollectionRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityCollectionService {
    private static final String DEFAULT_NAME = "Đã lưu";
    private static final Set<String> VISIBILITIES = Set.of("private", "organization", "guild", "restricted");
    private final CommunityRepository repository;
    private final CommunityCollectionRepository collections;
    private final CommunityPolicy policy;
    private final CommunityContentService contents;
    private final CommunityGuildService guilds;
    private final CommunityPayload payload;
    private final LearningIdentityService identity;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;

    public PageResponse<CollectionView> list(String q, boolean mine, String visibility, int page, int size) {
        var actor = policy.actor();
        CommunityPages.check(page, size);
        if (q != null && q.length() > 300 || visibility != null && !VISIBILITIES.contains(visibility)) {
            throw BusinessException.validation("visibility", "INVALID_VALUE", "Bộ lọc bộ sưu tập chưa hợp lệ.");
        }
        return PageResponse.from(collections.list(actor, q, mine, visibility, page, size).map(value -> view(value,
            actor)));
    }

    @Transactional
    public CollectionView create(CreateCollection request) {
        writeLock.acquire();
        var actor = policy.actor();
        String hash = payload.hash("collection", request);
        var replay = collections.request(actor.person().id(), request.clientRequestId());
        if (!replay.isEmpty()) {
            CommunityCollection existing = readable(replay.get(0).getCollectionId(), actor);
            if (!hash.equals(existing.getRequestHash())) {
                throw new BusinessException(CommunityResponseCode.IDEMPOTENCY_CONFLICT);
            }
            return view(existing, actor);
        }
        validate(request.name(), request.visibility(), request.primaryGuildId(), request.grants(), actor);
        CommunityCollection value = new CommunityCollection();
        value.setOwnerOrgId(actor.person().orgId());
        value.setOwnerUserId(actor.person().id());
        value.setName(request.name().trim());
        value.setDescription(request.description());
        value.setVisibility(request.visibility());
        value.setPrimaryGuildId(request.primaryGuildId());
        value.setStatus("active");
        value.setClientRequestId(request.clientRequestId());
        value.setRequestHash(hash);
        repository.save(value, actor.person().id());
        repository.flush();
        replaceGrants(value, request.grants(), actor);
        log(value, actor, "CREATED");
        repository.flush();
        return view(value, actor);
    }

    @Transactional
    public CollectionView update(Long id, UpdateCollection request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityCollection value = repository.require(CommunityCollection.class, id);
        if (!Objects.equals(value.getOwnerOrgId(), actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        requireOwner(value, actor);
        rejectDefault(value);
        policy.version(value, request.expectedVersion());
        validate(request.name(), request.visibility(), request.primaryGuildId(), request.grants(), actor);
        value.setName(request.name().trim());
        value.setDescription(request.description());
        value.setVisibility(request.visibility());
        value.setPrimaryGuildId(request.primaryGuildId());
        value.setStatus(request.status());
        replaceGrants(value, request.grants(), actor);
        repository.incrementVersion(value, actor.person().id());
        log(value, actor, "UPDATED");
        repository.flush();
        return view(value, actor);
    }

    @Transactional
    public void archive(Long id, Long expectedVersion) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityCollection value = readable(id, actor);
        requireOwner(value, actor);
        rejectDefault(value);
        policy.version(value, expectedVersion);
        value.setStatus("archived");
        repository.save(value, actor.person().id());
        log(value, actor, "ARCHIVED");
    }

    public CollectionItems items(Long id, int page, int size) {
        var actor = policy.actor();
        CommunityCollection value = readable(id, actor);
        CommunityPages.check(page, size);
        var items = collections.items(id, actor, page, size).map(item -> itemView(item, value, actor));
        return new CollectionItems(view(value, actor), PageResponse.from(items));
    }

    @Transactional
    public ItemResult item(Long id, Long contentId, SaveItem request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityCollection value = readable(id, actor);
        if (!canEditItems(value, actor)) {
            throw new AccessDeniedException("Collection editor required");
        }
        policy.version(value, request.expectedCollectionVersion());
        CollectionItem item = setItem(value, contentId, request.saved(), request.note(), request.sortNo(), actor);
        repository.flush();
        return new ItemResult(item == null ? null : itemView(item, value, actor), value.getRowVersion());
    }

    @Transactional
    public BookmarkView bookmark(Long contentId, boolean saved) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityCollection value = requireDefault(actor);
        setItem(value, contentId, saved, null, 0, actor);
        repository.flush();
        return new BookmarkView(contentId, saved);
    }

    @Transactional
    public PageResponse<ContentCard> bookmarks(int page, int size) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityCollection value = requireDefault(actor);
        CommunityPages.check(page, size);
        var items = collections.items(value.getCollectionId(), actor, page, size);
        return CommunityPages.of(contents.cards(items.stream().map(CollectionItem::getContentId).toList()), page, size,
            items.getTotalElements());
    }

    private CollectionItem setItem(CommunityCollection collection, Long contentId, boolean saved, String note,
                                   int sortNo, Actor actor) {
        CollectionItem existing = collections.item(collection.getCollectionId(), contentId);
        if (!saved) {
            if (existing != null) {
                repository.remove(existing);
                repository.incrementVersion(collection, actor.person().id());
                log(collection, actor, "ITEM_REMOVED");
            }
            return null;
        }
        CommunityContent content = policy.readable(contentId, actor);
        contents.requirePublished(content);
        if (existing != null && Objects.equals(existing.getNote(), note) && existing.getSortNo() == sortNo) {
            return existing;
        }
        CollectionItem item = existing == null ? new CollectionItem() : existing;
        item.setCollectionId(collection.getCollectionId());
        item.setContentId(contentId);
        item.setNote(note);
        item.setSortNo(sortNo);
        repository.save(item, actor.person().id());
        repository.incrementVersion(collection, actor.person().id());
        log(collection, actor, "ITEM_SAVED");
        return item;
    }

    private CommunityCollection requireDefault(Actor actor) {
        CommunityCollection value = collections.defaultCollection(actor.person().id());
        if (value == null) {
            value = new CommunityCollection();
            value.setOwnerOrgId(actor.person().orgId());
            value.setOwnerUserId(actor.person().id());
            value.setName(DEFAULT_NAME);
            value.setVisibility("private");
            value.setStatus("active");
            value.setClientRequestId(UUID.randomUUID());
            value.setRequestHash(payload.hash("default_collection", actor.person().id()));
            repository.save(value, actor.person().id());
            repository.flush();
        } else if (!Objects.equals(value.getOwnerOrgId(), actor.person().orgId())) {
            // Dấu trang private đi cùng tài khoản; bài cũ vẫn bị lọc theo org/ACL mới, không cấp lại quyền nguồn.
            value.setOwnerOrgId(actor.person().orgId());
            repository.incrementVersion(value, actor.person().id());
            log(value, actor, "OWNER_SCOPE_CHANGED");
        }
        return value;
    }

    private CommunityCollection readable(Long id, Actor actor) {
        if (!collections.accessible(id, actor)) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        return repository.require(CommunityCollection.class, id);
    }

    private boolean canEditItems(CommunityCollection value, Actor actor) {
        if (Objects.equals(value.getOwnerUserId(), actor.person().id())) {
            return true;
        }
        return collections.grants(value.getCollectionId()).stream().anyMatch(grant ->
            "editor".equals(grant.getPermission()) && (grant.getExpiresAt() == null
                || grant.getExpiresAt().isAfter(Instant.now()))
                && (Objects.equals(grant.getUserId(), actor.person().id())
                    || grant.getGuildId() != null && policy.member(grant.getGuildId(), actor)));
    }

    private void requireOwner(CommunityCollection value, Actor actor) {
        if (!Objects.equals(value.getOwnerUserId(), actor.person().id())) {
            throw new AccessDeniedException("Collection owner required");
        }
    }

    private void rejectDefault(CommunityCollection value) {
        if (DEFAULT_NAME.equals(value.getName())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
    }

    private void validate(String name, String visibility, Long guildId, List<GrantInput> grants, Actor actor) {
        if (DEFAULT_NAME.equals(name.trim()) || !VISIBILITIES.contains(visibility)
            || "guild".equals(visibility) != (guildId != null)
            || !"restricted".equals(visibility) && !grants.isEmpty()) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        if (guildId != null && !policy.member(guildId, actor)) {
            throw new AccessDeniedException("Active Guild membership required");
        }
        Set<String> subjects = new HashSet<>();
        for (GrantInput grant : grants) {
            if ((grant.userId() == null) == (grant.guildId() == null)
                || grant.expiresAt() != null && !grant.expiresAt().isAfter(Instant.now())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            if (grant.userId() != null) {
                var person = identity.activePerson(grant.userId());
                if (!Objects.equals(person.orgId(), actor.person().orgId())
                    || Objects.equals(person.id(), actor.person().id())) {
                    throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
                }
            } else {
                guilds.readable(grant.guildId(), actor);
            }
            if (!subjects.add(grant.userId() == null ? "g:" + grant.guildId() : "u:" + grant.userId())) {
                throw new BusinessException(CommunityResponseCode.DUPLICATE);
            }
        }
    }

    private void replaceGrants(CommunityCollection value, List<GrantInput> inputs, Actor actor) {
        collections.grants(value.getCollectionId()).forEach(repository::remove);
        for (GrantInput input : inputs) {
            CollectionAccessGrant grant = new CollectionAccessGrant();
            grant.setCollectionId(value.getCollectionId());
            grant.setUserId(input.userId());
            grant.setGuildId(input.guildId());
            grant.setPermission(input.permission());
            grant.setExpiresAt(input.expiresAt());
            grant.setStatus("active");
            repository.save(grant, actor.person().id());
        }
    }

    private CollectionView view(CommunityCollection value, Actor actor) {
        boolean owner = Objects.equals(value.getOwnerUserId(), actor.person().id());
        boolean isDefault = DEFAULT_NAME.equals(value.getName());
        String name = identity.fullNames(List.of(value.getOwnerUserId())).get(value.getOwnerUserId());
        var grants = owner ? collections.grants(value.getCollectionId()).stream().map(grant ->
            new GrantView(grant.getGrantId(), grant.getUserId(), grant.getGuildId(), grant.getPermission(),
                grant.getExpiresAt(), grant.getStatus())).toList() : List.<GrantView>of();
        return new CollectionView(value.getCollectionId(), new PersonRef(value.getOwnerUserId(), name), value.getName(),
            value.getDescription(), value.getVisibility(), value.getPrimaryGuildId(), value.getStatus(),
            collections.items(value.getCollectionId(), actor, 0, 1).getTotalElements(), isDefault,
            new CollectionCapabilities(owner && !isDefault, canEditItems(value, actor), owner && !isDefault), grants,
            value.getRowVersion(), value.getCreateTime(), value.getUpdateTime());
    }

    private CollectionItemView itemView(CollectionItem item, CommunityCollection value, Actor actor) {
        var content = policy.readable(item.getContentId(), actor);
        var detail = contents.detail(content, actor, false);
        return new CollectionItemView(item.getItemId(), value.getCollectionId(),
            new ContentRef(content.getContentId(), content.getKind(), detail.publishedRevision().revisionId(),
                detail.publishedRevision().title(), "/community/contents/" + content.getContentId()),
            item.getNote(), item.getSortNo(), value.getRowVersion());
    }

    private void log(CommunityCollection value, Actor actor, String action) {
        audit.record(actor.person().id(), "COMMUNITY_COLLECTION_" + action, "community_collection",
            value.getCollectionId().toString(), actor.person().orgId(), Map.of("collectionId",
                value.getCollectionId()));
    }
}
