package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.ModerationRequests.AssignReport;
import vn.com.coreacademy.community.dto.ModerationRequests.CreateReport;
import vn.com.coreacademy.community.dto.ModerationRequests.ModerationActionRequest;
import vn.com.coreacademy.community.dto.ModerationViews.ActionSummary;
import vn.com.coreacademy.community.dto.ModerationViews.CommentEvidence;
import vn.com.coreacademy.community.dto.ModerationViews.EvidenceView;
import vn.com.coreacademy.community.dto.ModerationViews.ReportCapabilities;
import vn.com.coreacademy.community.dto.ModerationViews.ReportCard;
import vn.com.coreacademy.community.dto.ModerationViews.ReportTarget;
import vn.com.coreacademy.community.dto.ModerationViews.ReportView;
import vn.com.coreacademy.community.entity.Answer;
import vn.com.coreacademy.community.entity.CommunityComment;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.CommunityRecord;
import vn.com.coreacademy.community.entity.CommunityReport;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.ModerationAction;
import vn.com.coreacademy.community.entity.Question;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityAccessRepository;
import vn.com.coreacademy.community.repository.CommunityModerationRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@RequiredArgsConstructor
public class CommunityReportService {
    private final CommunityRepository repository;
    private final CommunityModerationRepository reports;
    private final CommunityAccessRepository access;
    private final CommunityPolicy policy;
    private final CommunityContentService contents;
    private final CommunityLifecycleService lifecycle;
    private final LearningIdentityService identity;
    private final IdentityAuditService audit;
    private final IdentityWriteLock writeLock;

    @Transactional
    public ReportView create(CreateReport request) {
        writeLock.acquire();
        var actor = policy.actor();
        if ((request.contentId() == null) == (request.commentId() == null)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        CommunityReport report = new CommunityReport();
        report.setContentId(request.contentId());
        report.setCommentId(request.commentId());
        report.setReporterUserId(actor.person().id());
        report.setReasonCode(request.reasonCode());
        report.setDescription(request.description());
        report.setReportStatus("open");
        CommunityContent target = root(report);
        if (!policy.canRead(target, actor)
            && !(Objects.equals(target.getOwnerOrgId(), actor.person().orgId())
                && policy.isAuthorOrMaintainer(target, actor))) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        var existing = reports.duplicate(actor.person().id(), request.contentId(), request.commentId(),
            request.reasonCode());
        if (!existing.isEmpty()) {
            return view(existing.get(0), actor);
        }
        repository.save(report, actor.person().id());
        repository.flush();
        return view(report, actor);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReportCard> list(String status, boolean assigned, int page, int size) {
        var actor = policy.actor();
        policy.requireModerator(actor);
        CommunityPages.check(page, size);
        if (status != null && !Set.of("open", "in_review", "resolved", "dismissed").contains(status)) {
            throw BusinessException.validation("status", "INVALID_VALUE", "Trạng thái report chưa hợp lệ.");
        }
        var rows = reports.list(actor, status, assigned, page, size);
        preloadTargets(rows.getContent());
        Map<String, String> names = identity.fullNames(rows.getContent().stream()
            .map(CommunityReport::getAssignedModeratorUserId).filter(Objects::nonNull).collect(Collectors.toSet()));
        return PageResponse.from(rows.map(report -> {
            String moderator = report.getAssignedModeratorUserId();
            return new ReportCard(report.getReportId(), report.getReasonCode(), report.getReportStatus(),
                report.getCreateTime(), moderator == null ? null : new PersonRef(moderator, names.get(moderator)),
                report.getRowVersion(), report.getCreateTime(), report.getUpdateTime(), target(report, actor));
        }));
    }

    /**
     * Nạp sẵn bình luận, nội dung, câu hỏi cha của câu trả lời và bản đã đăng của cả trang vào persistence
     * context, để target() của từng thẻ đọc lại từ đó thay vì vài truy vấn mỗi thẻ.
     */
    private void preloadTargets(List<CommunityReport> rows) {
        Set<Long> commentIds = rows.stream().map(CommunityReport::getCommentId).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (!commentIds.isEmpty()) {
            repository.list("select c from CommunityComment c where c.commentId in :ids", CommunityComment.class,
                Map.of("ids", commentIds));
        }
        Set<Long> contentIds = rows.stream().map(report -> report.getContentId() != null ? report.getContentId()
            : repository.require(CommunityComment.class, report.getCommentId()).getContentId())
            .collect(Collectors.toSet());
        List<CommunityContent> contents = contentIds.isEmpty() ? List.of() : repository.list(
            "select c from CommunityContent c where c.contentId in :ids", CommunityContent.class,
            Map.of("ids", contentIds));
        Set<Long> answerIds = contents.stream().filter(c -> CommunityCodes.ANSWER.equals(c.getKind()))
            .map(CommunityContent::getContentId).collect(Collectors.toSet());
        if (!answerIds.isEmpty()) {
            Set<Long> questionIds = repository.list("select a from Answer a where a.contentId in :ids", Answer.class,
                Map.of("ids", answerIds)).stream().map(Answer::getQuestionContentId).collect(Collectors.toSet());
            repository.list("select c from CommunityContent c where c.contentId in :ids", CommunityContent.class,
                Map.of("ids", questionIds));
        }
        Set<Long> revisionIds = contents.stream().map(CommunityContent::getCurrentPublishedRevisionId)
            .filter(Objects::nonNull).collect(Collectors.toSet());
        if (!revisionIds.isEmpty()) {
            repository.list("select r from ContentRevision r where r.revisionId in :ids", ContentRevision.class,
                Map.of("ids", revisionIds));
        }
    }

    /**
     * Moderator chưa được giao chỉ nhận metadata như hàng đợi cùng loại và ID đích; mô tả, người báo cáo và lịch
     * sử xử lý có thể chứa trích dẫn riêng tư nên chỉ trả cho moderator đang xử lý case.
     */
    @Transactional(readOnly = true)
    public ReportView detail(Long id) {
        var actor = policy.actor();
        policy.requireModerator(actor);
        ReportView view = view(scoped(id, actor), actor);
        if (view.capabilities().canReadEvidence()) {
            return view;
        }
        return new ReportView(view.reportId(), view.reasonCode(), view.status(), view.reportedAt(),
            view.assignedModerator(), view.version(), view.createTime(), view.updateTime(), view.targetType(),
            view.targetId(), null, null, List.of(), view.capabilities(), view.target());
    }

    @Transactional
    public ReportView assign(Long id, AssignReport request) {
        writeLock.acquire();
        var actor = policy.actor();
        policy.requireModerator(actor);
        CommunityReport report = scoped(id, actor);
        policy.version(report, request.expectedVersion());
        if (!Set.of("open", "in_review").contains(report.getReportStatus())
            || !access.moderator(request.moderatorUserId(), actor.person().orgId())
            || !Objects.equals(identity.activePerson(request.moderatorUserId()).orgId(), actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        report.setAssignedModeratorUserId(request.moderatorUserId());
        report.setReportStatus("in_review");
        repository.save(report, actor.person().id());
        audit.record(actor.person().id(), "COMMUNITY_REPORT_ASSIGNED", "community_report", id.toString(),
            actor.person().orgId(), Map.of("moderatorUserId", request.moderatorUserId()));
        repository.flush();
        return view(report, actor);
    }

    @Transactional
    public EvidenceView evidence(Long id) {
        var actor = policy.actor();
        CommunityReport report = assigned(id, actor);
        CommunityContent content = root(report);
        audit.record(actor.person().id(), "COMMUNITY_EVIDENCE_READ", "community_report", id.toString(),
            actor.person().orgId(), Map.of("contentId", content.getContentId()));
        Long revisionId = content.getCurrentPublishedRevisionId() != null ? content.getCurrentPublishedRevisionId()
            : content.getCurrentDraftRevisionId();
        CommunityComment comment = report.getCommentId() == null ? null
            : repository.require(CommunityComment.class, report.getCommentId());
        return new EvidenceView(id, comment == null ? "content" : "comment",
            comment == null ? content.getContentId() : comment.getCommentId(),
            comment == null ? content.getRowVersion() : comment.getRowVersion(),
            comment == null && revisionId != null
                ? contents.revision(repository.require(ContentRevision.class, revisionId), content) : null,
            comment == null ? null : new CommentEvidence(comment.getCommentId(), comment.getContentId(),
                comment.getRevisionId(), comment.getBodyMarkdown(), comment.getStatus(), comment.getDeletedAt()),
            revisionId, actions(report));
    }

    @Transactional
    public ReportView action(Long id, ModerationActionRequest request) {
        writeLock.acquire();
        var actor = policy.actor();
        CommunityReport report = assigned(id, actor);
        policy.version(report, request.expectedReportVersion());
        CommunityContent content = root(report);
        CommunityComment comment = report.getCommentId() == null ? null
            : repository.require(CommunityComment.class, report.getCommentId());
        CommunityRecord target = comment == null ? content : comment;
        policy.version(target, request.expectedTargetVersion());
        JsonNode before = state(content, comment);
        String action = request.action();
        if (!"assign_maintainer".equals(action) && request.maintainerUserId() != null
            || !Set.of("restore", "restore_deleted").contains(action) && request.restoreActionId() != null) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        switch (action) {
            case "hide" -> {
                if (comment == null) {
                    content.setLifecycleStatus("hidden");
                    lifecycle.clearAcceptedAnswer(content, actor.person().id());
                } else {
                    comment.setStatus("hidden");
                }
            }
            case "soft_delete" -> {
                if (comment == null) {
                    content.setDeletedAt(Instant.now());
                    content.setDeletedBy(actor.person().id());
                    lifecycle.clearAcceptedAnswer(content, actor.person().id());
                } else {
                    comment.setDeletedAt(Instant.now());
                    comment.setDeletedBy(actor.person().id());
                }
            }
            case "close" -> {
                if (comment != null || !"question".equals(content.getKind())
                    || !"active".equals(content.getLifecycleStatus()) || content.getDeletedAt() != null) {
                    throw new BusinessException(CommunityResponseCode.INVALID_STATE);
                }
                content.setLifecycleStatus("closed");
                Question question = repository.require(Question.class, content.getContentId());
                question.setClosedReason(request.reason());
                repository.save(question, actor.person().id());
            }
            case "assign_maintainer" -> {
                if (comment != null || request.maintainerUserId() == null
                    || !Objects.equals(identity.activePerson(request.maintainerUserId()).orgId(),
                        content.getOwnerOrgId())) {
                    throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
                }
                content.setMaintainerUserId(request.maintainerUserId());
            }
            case "restore", "restore_deleted" -> restore(report, content, comment, request);
            case "warn", "dismiss" -> {
                // Quyết định không sửa payload của tác giả; vẫn lưu action và lý do append-only.
            }
            default -> throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        repository.incrementVersion(target, actor.person().id());
        ModerationAction entry = new ModerationAction();
        entry.setReportId(id);
        entry.setActorUserId(actor.person().id());
        entry.setActionType(action);
        entry.setReason(request.reason());
        entry.setPreviousStateJson(before);
        entry.setNewStateJson(state(content, comment));
        entry.setOccurredAt(Instant.now());
        repository.save(entry, actor.person().id());
        report.setReportStatus("dismiss".equals(action) ? "dismissed" : "resolved");
        report.setResolvedAt(Instant.now());
        repository.save(report, actor.person().id());
        var outcome = JsonNodeFactory.instance.objectNode();
        outcome.putArray("recipientUserIds").add(comment == null ? content.getAuthorUserId()
            : comment.getAuthorUserId());
        outcome.put("reportId", id);
        outcome.put("action", action);
        contents.event(content, "content_moderated", actor.person().id(), outcome);
        repository.flush();
        return view(report, actor);
    }

    private void restore(CommunityReport report, CommunityContent content, CommunityComment comment,
                         ModerationActionRequest request) {
        ModerationAction previous = reports.latestTargetAction(report);
        if (previous == null || !Objects.equals(previous.getActionId(), request.restoreActionId())
            || !Set.of("hide", "soft_delete", "close").contains(previous.getActionType())
            || !state(content, comment).equals(previous.getNewStateJson())) {
            throw new BusinessException(CommunityResponseCode.CONFLICT);
        }
        boolean restoreDeleted = "restore_deleted".equals(request.action());
        if (restoreDeleted != "soft_delete".equals(previous.getActionType())
            || !restoreDeleted && (comment == null ? content.getDeletedAt() : comment.getDeletedAt()) != null) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        JsonNode before = previous.getPreviousStateJson();
        if (comment == null) {
            content.setLifecycleStatus(before.path("status").asText());
            if (restoreDeleted) {
                content.setDeletedAt(null);
                content.setDeletedBy(null);
            }
        } else {
            comment.setStatus(before.path("status").asText());
            if (restoreDeleted) {
                comment.setDeletedAt(null);
                comment.setDeletedBy(null);
            }
        }
    }

    private JsonNode state(CommunityContent content, CommunityComment comment) {
        ObjectNode state = JsonNodeFactory.instance.objectNode();
        state.put("status", comment == null ? content.getLifecycleStatus() : comment.getStatus());
        Instant deleted = comment == null ? content.getDeletedAt() : comment.getDeletedAt();
        state.put("deletedAt", deleted == null ? null : deleted.toString());
        return state;
    }

    public CommunityReport assigned(Long id, Actor actor) {
        policy.requireModerator(actor);
        CommunityReport report = scoped(id, actor);
        if (!"in_review".equals(report.getReportStatus())
            || !Objects.equals(report.getAssignedModeratorUserId(), actor.person().id())) {
            throw new AccessDeniedException("Assigned active moderator case required");
        }
        return report;
    }

    private CommunityReport scoped(Long id, Actor actor) {
        CommunityReport report = repository.require(CommunityReport.class, id);
        if (!Objects.equals(root(report).getOwnerOrgId(), actor.person().orgId())) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        return report;
    }

    private CommunityContent root(CommunityReport report) {
        Long id = report.getContentId() != null ? report.getContentId()
            : repository.require(CommunityComment.class, report.getCommentId()).getContentId();
        return repository.require(CommunityContent.class, id);
    }

    private PersonRef person(String userId) {
        return userId == null ? null : new PersonRef(userId, identity.fullNames(List.of(userId)).get(userId));
    }

    private List<ActionSummary> actions(CommunityReport report) {
        return reports.actions(report.getReportId()).stream().map(action -> new ActionSummary(action.getActionId(),
            action.getActionType(), action.getReason(), person(action.getActorUserId()), action.getOccurredAt()))
                .toList();
    }

    private ReportView view(CommunityReport report, Actor actor) {
        boolean assigned = Objects.equals(report.getAssignedModeratorUserId(), actor.person().id())
            && access.moderator(actor.person().id(), actor.person().orgId())
                && "in_review".equals(report.getReportStatus());
        return new ReportView(report.getReportId(), report.getReasonCode(), report.getReportStatus(),
            report.getCreateTime(),
            person(report.getAssignedModeratorUserId()), report.getRowVersion(), report.getCreateTime(),
                report.getUpdateTime(),
            report.getContentId() != null ? "content" : "comment",
            report.getContentId() != null ? report.getContentId() : report.getCommentId(), report.getDescription(),
            person(report.getReporterUserId()), actions(report), new ReportCapabilities(
                access.moderator(actor.person().id(), actor.person().orgId()), assigned, assigned),
            target(report, actor));
    }

    /** Tiêu đề chỉ trả khi moderator đọc được nội dung theo ACL thường; role moderator không mở bài private. */
    private ReportTarget target(CommunityReport report, Actor actor) {
        CommunityContent content = root(report);
        String title = null;
        if (policy.canRead(content, actor) && content.getCurrentPublishedRevisionId() != null) {
            title = repository.require(ContentRevision.class, content.getCurrentPublishedRevisionId()).getTitle();
        }
        return new ReportTarget(report.getCommentId() != null ? "comment" : content.getKind(),
            content.getContentId(), title);
    }
}
