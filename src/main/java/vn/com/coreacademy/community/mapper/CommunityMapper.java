package vn.com.coreacademy.community.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.community.dto.ContentViews.CommentView;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.GrantView;
import vn.com.coreacademy.community.dto.ContentViews.RevisionAssetView;
import vn.com.coreacademy.community.dto.ContentViews.RevisionDetail;
import vn.com.coreacademy.community.dto.ContentViews.TagView;
import vn.com.coreacademy.community.entity.CommunityComment;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.CommunityTag;
import vn.com.coreacademy.community.entity.ContentAccessGrant;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.RevisionAsset;

@Mapper(config = CentralMapperConfig.class)
public interface CommunityMapper {
    @Mapping(target = "version", source = "tag.rowVersion")
    TagView tag(CommunityTag tag, List<String> aliases, long usageCount);

    @Mapping(target = "context", source = "revision.contextJson")
    @Mapping(target = "revisionVersion", source = "revision.rowVersion")
    @Mapping(target = "editor", ignore = true)
    RevisionDetail revision(ContentRevision revision, Long contentVersion, List<RevisionAssetView> assets);

    RevisionAssetView asset(RevisionAsset asset);

    @Mapping(target = "version", source = "rowVersion")
    @Mapping(target = "author", ignore = true)
    @Mapping(target = "isDeleted", ignore = true)
    @Mapping(target = "capabilities", ignore = true)
    CommentView comment(CommunityComment comment);

    GrantView grant(ContentAccessGrant grant);

    @Mapping(target = "contentId", source = "content.contentId")
    @Mapping(target = "title", source = "revision.title")
    @Mapping(target = "summary", source = "revision.summary")
    @Mapping(target = "publishedAt", source = "revision.publishedAt")
    @Mapping(target = "contentVersion", source = "content.rowVersion")
    @Mapping(target = "publishedRevisionId", source = "content.currentPublishedRevisionId")
    @Mapping(target = "author", ignore = true)
    @Mapping(target = "tags", ignore = true)
    @Mapping(target = "score", ignore = true)
    @Mapping(target = "myVote", ignore = true)
    @Mapping(target = "capabilities", ignore = true)
    @Mapping(target = "resolutionStatus", ignore = true)
    @Mapping(target = "answerCount", ignore = true)
    @Mapping(target = "acceptedAnswerId", ignore = true)
    @Mapping(target = "isDuplicate", ignore = true)
    @Mapping(target = "duplicateTarget", ignore = true)
    @Mapping(target = "questionId", ignore = true)
    @Mapping(target = "revisionId", ignore = true)
    @Mapping(target = "isAccepted", ignore = true)
    @Mapping(target = "acceptedRevisionId", ignore = true)
    @Mapping(target = "acceptedRevisionIsCurrent", ignore = true)
    @Mapping(target = "hasDraft", ignore = true)
    @Mapping(target = "occurredOn", ignore = true)
    @Mapping(target = "coverRevisionAssetId", ignore = true)
    ContentCard card(CommunityContent content, ContentRevision revision);
}
