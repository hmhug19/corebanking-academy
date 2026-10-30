package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;

public final class ContentViews {
    public record TagView(Long tagId, String slug, String name, String description, String status,
                          List<String> aliases, Long version, Long mergedIntoTagId, long usageCount,
                          Instant createTime, Instant updateTime) {
    }

    public record RevisionAssetView(Long revisionAssetId, Long assetId, String usage, String caption,
                                    String altText, Integer sortNo) {
    }

    public record RevisionDetail(Long revisionId, Long contentId, Integer revisionNo, String title,
                                 String bodyMarkdown, String summary, JsonNode context, String revisionStatus,
                                 Instant publishedAt, Long revisionVersion, Long contentVersion,
                                 List<RevisionAssetView> assets, String changeNote, PersonRef editor,
                                 Instant updateTime) {
    }

    public record ContentCard(Long contentId, String kind, String title, String summary, String authorUserId,
                              String lifecycleStatus, String visibility, Long primaryGuildId, Instant publishedAt,
                              Instant lastActivityAt, Long contentVersion, Long publishedRevisionId,
                              PersonRef author, List<TagView> tags, long score, int myVote, Capabilities capabilities,
                              String resolutionStatus, long answerCount, Long acceptedAnswerId, boolean isDuplicate,
                              ContentRef duplicateTarget, Long questionId, Long revisionId, boolean isAccepted,
                              Long acceptedRevisionId, boolean acceptedRevisionIsCurrent, boolean hasDraft,
                              String occurredOn) {
    }

    public record ContentDetail(Long contentId, String kind, Long ownerOrgId, PersonRef author, PersonRef maintainer,
                                String lifecycleStatus, String visibility, Long primaryGuildId, Long contentVersion,
                                RevisionDetail publishedRevision, RevisionDetail draftRevision,
                                List<TagView> tags, Capabilities capabilities, String resolutionStatus,
                                Long acceptedAnswerId, Long acceptedRevisionId, boolean acceptedRevisionIsCurrent,
                                long answerCount, long score, int myVote, boolean saved, String subscriptionMode,
                                boolean isDuplicate, ContentRef duplicateTarget, Closure closure,
                                Long questionId) {
    }

    /**
     * Lần đóng làm câu hỏi đang đóng: action là close, duplicate hoặc moderated. Với moderated (điều phối
     * viên xử lý qua hồ sơ báo cáo), reason và actor luôn null.
     */
    public record Closure(String action, String reason, PersonRef actor, Instant occurredAt) {
    }

    public record PersonRef(String userId, String displayName) {
    }

    /** Các cờ vòng đời (canClose..canMarkDuplicate) chỉ tính ở chi tiết; card trong danh sách luôn trả false. */
    public record Capabilities(boolean canEditDraft, boolean canPublish, boolean canAnswer, boolean canComment,
                               boolean canVote, boolean canAccept, boolean canManageAccess, boolean canReport,
                               boolean canClose, boolean canReopen, boolean canArchive, boolean canDelete,
                               boolean canMarkDuplicate) {
        public static final Capabilities NONE = new Capabilities(false, false, false, false, false, false, false,
            false, false, false, false, false, false);
    }

    public record VoteView(Long contentId, long score, long upvotes, long downvotes, int myVote) {
    }

    public record CommentView(Long commentId, Long contentId, String authorUserId, Long parentCommentId,
                              Long reviewThreadId, String bodyMarkdown, String status, Instant createTime,
                              Long version, PersonRef author, Long revisionId, boolean isDeleted,
                              Map<String, Boolean> capabilities, Instant updateTime) {
    }

    public record GrantView(Long grantId, String userId, Long guildId, String permission, Instant expiresAt,
        String status) {
    }

    public record AccessView(Long contentId, String visibility, Long primaryGuildId, List<GrantView> grants,
                             Long contentVersion, Map<String, Boolean> capabilities) {
    }

    private ContentViews() {
    }
}
