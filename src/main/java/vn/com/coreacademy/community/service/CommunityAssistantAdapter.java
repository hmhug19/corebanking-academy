package vn.com.coreacademy.community.service;

import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.constants.CommunityLimits;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.ContentRepository;
import vn.com.coreacademy.identity.service.LearningIdentityService;

/** Cổng đọc cho AgentContext do server chụp trước stream; không nhận caller ID từ tool arguments của model. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityAssistantAdapter {
    private static final int MAX_RESULTS = 5;
    private static final int SNIPPET_CHARS = 200;
    private final LearningIdentityService identity;
    private final ContentRepository contents;
    private final CommunityRepository repository;

    /** One readable post; {@code snippet} is a short plain-text lead, for the model only, never shown as a link. */
    public record Source(Long contentId, Long revisionId, String title, String url, String snippet) {
        public Source(Long contentId, Long revisionId, String title, String url) {
            this(contentId, revisionId, title, url, "");
        }
    }

    private static String snippetOf(ContentRevision revision) {
        String text = revision.getSummary() != null && !revision.getSummary().isBlank() ? revision.getSummary()
            : revision.getBodyMarkdown();
        if (text == null) {
            return "";
        }
        String flat = text.strip().replaceAll("\\s+", " ");

        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS).strip() + "…";
    }

    public List<Source> search(String trustedUserId, String query) {
        if (query == null || query.isBlank() || query.length() > CommunityLimits.QUERY) {
            throw BusinessException.validation("query", "INVALID_VALUE", "Từ khóa phải từ 1 đến 300 ký tự.");
        }
        var actor = identity.actor(trustedUserId);
        identity.activeOrg(actor.person().orgId());
        // Repository áp ACL trước count/limit; không truyền SQL hoặc org do model chọn.
        var input = new ContentRepository.Search(null, query, List.of(), null, null, false,
            "published", null, 0, MAX_RESULTS, "publishedAt");
        return contents.search(input, actor.person().id(), actor.person().orgId()).stream().map(content -> {
            var revision = repository.require(ContentRevision.class, content.getCurrentPublishedRevisionId());
            return new Source(content.getContentId(), revision.getRevisionId(), revision.getTitle(),
                "/community/contents/" + content.getContentId() + "?revisionId=" + revision.getRevisionId(),
                snippetOf(revision));
        }).toList();
    }

    public boolean stillReadable(String trustedUserId, List<Source> sources) {
        var actor = identity.actor(trustedUserId);
        identity.activeOrg(actor.person().orgId());
        var visible = contents.publishedBatch(sources.stream().map(Source::contentId).toList(), actor);
        return sources.stream().allMatch(source -> visible.stream().anyMatch(content ->
            Objects.equals(content.getContentId(), source.contentId())
                && Objects.equals(content.getCurrentPublishedRevisionId(), source.revisionId())));
    }
}
