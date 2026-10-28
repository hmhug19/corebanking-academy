package vn.com.coreacademy.community.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.LinkDtos.LinkResult;
import vn.com.coreacademy.community.dto.LinkDtos.LinkView;
import vn.com.coreacademy.community.dto.LinkDtos.Links;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.ContentLink;
import vn.com.coreacademy.community.entity.QuestionLink;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityLinkRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityLinkService {
    private final CommunityRepository repository;
    private final CommunityLinkRepository links;
    private final CommunityPolicy policy;
    private final CommunityContentService contents;
    private final IdentityWriteLock writeLock;

    public PageResponse<LinkView> list(Long contentId, String relationType, int page, int size) {
        CommunityPages.check(page, size);
        var actor = policy.actor();
        policy.readable(contentId, actor);
        if (relationType != null && !Set.of("related", "summarizes", "supersedes", "fulfills", "follow_up",
            "learning_reference", "duplicate").contains(relationType)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        return PageResponse.from(links.visible(contentId, relationType, actor, page, size));
    }

    /** SET chỉ thay các relation được phép; duplicate/fulfills/follow_up giữ workflow chuyên biệt của chúng. */
    @Transactional
    public LinkResult replace(Long contentId, Links request) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = policy.readable(contentId, actor);
        policy.requirePublisher(content, actor);
        policy.version(content, request.expectedContentVersion());
        Set<String> grain = new HashSet<>();
        for (var input : request.links()) {
            int count = (input.targetContentId() == null ? 0 : 1) + (input.targetCourseId() == null ? 0 : 1)
                + (input.targetKnowledgeDocumentId() == null ? 0 : 1);
            if (count != 1) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            String type = input.targetContentId() != null ? "community"
                : input.targetCourseId() != null ? "learning" : "knowledge";
            Long id = input.targetContentId() != null ? input.targetContentId()
                : input.targetCourseId() != null ? input.targetCourseId() : input.targetKnowledgeDocumentId();
            if (!grain.add(type + ":" + id + ":" + input.relationType())) {
                throw new BusinessException(CommunityResponseCode.DUPLICATE);
            }
            if (!links.readableTarget(type, id, actor) || "community".equals(type) && Objects.equals(id, contentId)) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            if ("supersedes".equals(input.relationType())
                && (!"community".equals(type) || links.supersedesCycle(contentId, id))) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
        }
        links.editableLinks(contentId).forEach(repository::remove);
        links.relatedQuestions(contentId).forEach(repository::remove);
        for (var input : request.links()) {
            boolean relatedQuestions = "question".equals(content.getKind()) && "related".equals(input.relationType())
                && input.targetContentId() != null && "question".equals(repository.require(CommunityContent.class,
                    input.targetContentId()).getKind());
            if (relatedQuestions) {
                QuestionLink link = new QuestionLink();
                link.setSourceQuestionId(contentId);
                link.setTargetQuestionId(input.targetContentId());
                link.setRelationType("related");
                repository.save(link, actor.person().id());
            } else {
                ContentLink link = new ContentLink();
                link.setSourceContentId(contentId);
                link.setTargetContentId(input.targetContentId());
                link.setTargetCourseId(input.targetCourseId());
                link.setTargetKnowledgeDocumentId(input.targetKnowledgeDocumentId());
                link.setRelationType(input.relationType());
                repository.save(link, actor.person().id());
            }
        }
        repository.incrementVersion(content, actor.person().id());
        contents.event(content, "content_links_changed", actor.person().id());
        repository.flush();
        List<LinkView> result = new ArrayList<>();
        for (int page = 0; ; page++) {
            var values = links.visible(contentId, null, actor, page, 100);
            result.addAll(values.getContent());
            if (!values.hasNext()) {
                break;
            }
        }
        return new LinkResult(content.getRowVersion(), result);
    }
}
