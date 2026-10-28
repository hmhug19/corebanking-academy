package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;

import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.ContentViews.RevisionDetail;

public final class ModerationViews {
    public record ReportCard(Long reportId, String reasonCode, String status, Instant reportedAt,
                              PersonRef assignedModerator, Long version, Instant createTime, Instant updateTime) {
    }

    public record ActionSummary(Long actionId, String actionType, String reason, PersonRef actor, Instant occurredAt) {
    }

    public record ReportCapabilities(boolean canAssign, boolean canReadEvidence, boolean canAct) {
    }

    public record ReportView(Long reportId, String reasonCode, String status, Instant reportedAt,
                              PersonRef assignedModerator, Long version, Instant createTime, Instant updateTime,
                              String targetType, Long targetId, String description, PersonRef reporter,
                              List<ActionSummary> actions, ReportCapabilities capabilities) {
    }

    public record CommentEvidence(Long commentId, Long contentId, Long revisionId, String bodyMarkdown,
                                   String status, Instant deletedAt) {
    }

    public record EvidenceView(Long reportId, String targetType, Long targetId, Long targetVersion,
                                RevisionDetail content, CommentEvidence comment, Long revisionId,
                                List<ActionSummary> actions) {
    }

    private ModerationViews() {
    }
}
