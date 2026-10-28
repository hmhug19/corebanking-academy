package vn.com.coreacademy.community.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.constants.CommunityLimits;
import vn.com.coreacademy.community.dto.ContentRequests.CreateTag;
import vn.com.coreacademy.community.dto.ContentRequests.UpdateTag;
import vn.com.coreacademy.community.dto.ContentViews.TagView;
import vn.com.coreacademy.community.entity.CommunityTag;
import vn.com.coreacademy.community.entity.TagAlias;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.mapper.CommunityMapper;
import vn.com.coreacademy.community.repository.CommunityRelations;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.CommunityTagReadRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityTagService {
    private final CommunityRepository repository;
    private final CommunityRelations relations;
    private final CommunityPolicy policy;
    private final CommunityMapper mapper;
    private final IdentityWriteLock writeLock;
    private final CommunityTagReadRepository tagRead;

    public PageResponse<TagView> list(String q, int page, int size) {
        var actor = policy.actor();
        CommunityPages.check(page, size);
        if (q != null && q.length() > CommunityLimits.QUERY) {
            throw BusinessException.validation("q", "TOO_LONG", "Từ khóa tối đa 300 ký tự.");
        }
        String term = q == null ? "" : q.trim();
        var tags = relations.tagPage(actor.person().orgId(), term, page * size, size);
        var usage = tagRead.usage(tags.stream().map(CommunityTag::getTagId).toList(), actor);
        Map<Long, List<TagAlias>> aliases = relations.aliases(tags.stream().map(CommunityTag::getTagId).toList())
            .stream().collect(Collectors.groupingBy(TagAlias::getTagId));
        return CommunityPages.of(tags.stream().map(tag -> mapper.tag(tag,
            aliases.getOrDefault(tag.getTagId(), List.of()).stream()
                .map(TagAlias::getNormalizedAlias).toList(), usage.getOrDefault(tag.getTagId(), 0L))).toList(),
            page, size, relations.tagCount(actor.person().orgId(), term));
    }

    @Transactional
    public TagView create(CreateTag request) {
        writeLock.acquire();
        var actor = policy.actor();
        policy.requireModerator(actor);
        CommunityTag tag = new CommunityTag();
        tag.setOwnerOrgId(actor.person().orgId());
        tag.setSlug(request.slug());
        tag.setName(request.name().trim());
        tag.setDescription(request.description());
        tag.setStatus(CommunityCodes.ACTIVE);
        validateNames(tag, request.aliases());
        repository.save(tag, actor.person().id());
        repository.flush();
        replaceAliases(tag, request.aliases(), actor.person().id());
        repository.flush();
        return view(tag);
    }

    @Transactional
    public TagView update(Long id, UpdateTag request) {
        writeLock.acquire();
        var actor = policy.actor();
        policy.requireModerator(actor);
        CommunityTag tag = repository.require(CommunityTag.class, id);
        if (!actor.person().orgId().equals(tag.getOwnerOrgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        policy.version(tag, request.expectedVersion());
        if (tag.getMergedIntoTagId() != null) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        validateNames(tag, request.aliases());
        tag.setName(request.name().trim());
        tag.setDescription(request.description());
        tag.setStatus(request.status());
        replaceAliases(tag, request.aliases(), actor.person().id());
        repository.incrementVersion(tag, actor.person().id());
        repository.flush();
        return view(tag);
    }

    private void validateNames(CommunityTag tag, List<String> aliases) {
        Set<String> names = new HashSet<>();
        names.add(tag.getSlug());
        for (String alias : aliases == null ? List.<String>of() : aliases) {
            if (!names.add(alias.trim().toLowerCase(Locale.ROOT))) {
                throw new BusinessException(CommunityResponseCode.DUPLICATE);
            }
        }
        for (String name : names) {
            if (relations.tagNameUsed(tag.getOwnerOrgId(), name, tag.getTagId())) {
                throw new BusinessException(CommunityResponseCode.DUPLICATE);
            }
        }
    }

    private void replaceAliases(CommunityTag tag, List<String> aliases, String actorId) {
        relations.aliases(List.of(tag.getTagId())).forEach(repository::remove);
        for (String text : aliases == null ? List.<String>of() : aliases) {
            TagAlias alias = new TagAlias();
            alias.setTagId(tag.getTagId());
            alias.setNormalizedAlias(text.trim().toLowerCase(Locale.ROOT));
            repository.save(alias, actorId);
        }
    }

    public TagView view(CommunityTag tag) {
        return mapper.tag(tag, relations.aliases(List.of(tag.getTagId())).stream()
            .map(TagAlias::getNormalizedAlias).toList(), tagRead.usage(List.of(tag.getTagId()), policy.actor())
                .getOrDefault(tag.getTagId(), 0L));
    }
}
