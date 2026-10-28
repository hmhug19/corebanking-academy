package vn.com.coreacademy.community.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.LearningResourceDtos.LearningResourceView;
import vn.com.coreacademy.community.dto.LearningResourceDtos.ResourceResult;
import vn.com.coreacademy.community.dto.LearningResourceDtos.Resources;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.LearningLink;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityLearningRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.ContentRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.service.CommunityLearningAdapter;
import vn.com.coreacademy.learning.service.LearningResourceExtension;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityLearningService implements LearningResourceExtension {
    private final CommunityRepository repository;
    private final CommunityLearningRepository learning;
    private final CommunityLearningAdapter courses;
    private final CommunityContentService contents;
    private final CommunityPolicy policy;
    private final IdentityWriteLock writeLock;

    public PageResponse<LearningResourceView> list(Long revisionId, Long lessonId, int page, int size) {
        CommunityPages.check(page, size);
        var context = courses.readable(revisionId);
        lesson(context.lessonIds(), lessonId);
        var actor = policy.actor();
        return PageResponse.from(learning.visible(revisionId, lessonId, actor, page, size)
            .map(value -> view(value, actor, context.canManage() && "draft".equals(context.state()))));
    }

    /** Hai cổng quyền trong cùng transaction/khóa; chỉ thay link của draft, không copy bytes sang Learning. */
    @Transactional
    public ResourceResult replace(Long revisionId, Resources request) {
        writeLock.acquire();
        var actor = policy.actor();
        var context = courses.editable(revisionId);
        Set<String> grain = new HashSet<>();
        for (var item : request.items()) {
            lesson(context.lessonIds(), item.lessonId());
            if (!grain.add(item.lessonId() + ":" + item.contentRevisionId())) {
                throw new BusinessException(CommunityResponseCode.DUPLICATE);
            }
            publishable(item.contentRevisionId(), context.ownerOrgId(), actor);
        }
        var version = courses.advance(revisionId, request.expectedCourseRevisionVersion());
        learning.links(revisionId).forEach(repository::remove);
        List<LearningLink> result = new ArrayList<>();
        for (var item : request.items()) {
            LearningLink link = new LearningLink();
            link.setCourseRevisionId(revisionId);
            link.setLessonId(item.lessonId());
            link.setContentRevisionId(item.contentRevisionId());
            link.setSortNo(item.sortNo());
            link.setStatus("active");
            repository.save(link, actor.person().id());
            result.add(link);
        }
        repository.flush();
        return new ResourceResult(version, result.stream().map(value -> view(value, actor, true)).toList());
    }

    public PageResponse<ContentCard> candidates(String q, String kind, int page, int size) {
        return contents.list(new ContentRepository.Search(kind, q, null, null, null, false,
            null, "active", page, size, "publishedAt"), true);
    }

    @Override
    public void validateBeforePublish(Long courseRevisionId) {
        var context = courses.editable(courseRevisionId);
        var actor = policy.actor();
        for (var link : learning.links(courseRevisionId)) {
            lesson(context.lessonIds(), link.getLessonId());
            publishable(link.getContentRevisionId(), context.ownerOrgId(), actor);
        }
    }

    @Override
    @Transactional
    public void cloneResources(Long sourceRevisionId, Long targetRevisionId, Map<Long, Long> lessonIds) {
        var actor = policy.actor();
        var target = courses.editable(targetRevisionId);
        for (var source : learning.links(sourceRevisionId)) {
            publishable(source.getContentRevisionId(), target.ownerOrgId(), actor);
            if (source.getLessonId() != null && !lessonIds.containsKey(source.getLessonId())) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            LearningLink link = new LearningLink();
            link.setCourseRevisionId(targetRevisionId);
            link.setLessonId(source.getLessonId() == null ? null : lessonIds.get(source.getLessonId()));
            link.setContentRevisionId(source.getContentRevisionId());
            link.setSortNo(source.getSortNo());
            link.setStatus("active");
            repository.save(link, actor.person().id());
        }
    }

    private void publishable(Long revisionId, Long orgId, Actor actor) {
        var revision = repository.require(ContentRevision.class, revisionId);
        var content = policy.readable(revision.getContentId(), actor);
        if (!Objects.equals(content.getOwnerOrgId(), orgId) || revision.getDeletedAt() != null
            || !Set.of("published", "superseded").contains(revision.getRevisionStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        policy.requirePublisher(content, actor);
    }

    private void lesson(List<Long> allowed, Long lessonId) {
        if (lessonId != null && !allowed.contains(lessonId)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
    }

    private LearningResourceView view(LearningLink link, Actor actor, boolean editable) {
        var revision = repository.require(ContentRevision.class, link.getContentRevisionId());
        var content = policy.readable(revision.getContentId(), actor);
        return new LearningResourceView(link.getLearningLinkId(), link.getCourseRevisionId(), link.getLessonId(),
            new ContentRef(content.getContentId(), content.getKind(), revision.getRevisionId(), revision.getTitle(),
                "/community/contents/" + content.getContentId() + "?revisionId=" + revision.getRevisionId()),
            link.getSortNo(), link.getStatus(), editable ? List.of("remove") : List.of());
    }
}
