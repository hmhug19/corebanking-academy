package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.entity.Answer;
import vn.com.coreacademy.community.entity.CommunityComment;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.CommunityMention;
import vn.com.coreacademy.community.entity.CommunityNotification;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;
import vn.com.coreacademy.community.entity.CommunityUserPreferences;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.NotificationDigestBatch;
import vn.com.coreacademy.community.entity.NotificationDigestItem;
import vn.com.coreacademy.community.repository.CommunityFanoutRepository;
import vn.com.coreacademy.community.repository.CommunityNotificationRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.LearningIdentityService;

/** Mọi phương thức được worker gọi trong transaction có khóa/lease; không gửi mail hay dữ liệu ra ngoài. */
@Service
@RequiredArgsConstructor
public class CommunityFanoutService {
    private final CommunityRepository repository;
    private final CommunityFanoutRepository fanout;
    private final CommunityNotificationRepository notifications;
    private final CommunityPayload payload;
    private final CommunityPolicy policy;
    private final CommunityPreferenceService preferences;
    private final LearningIdentityService identity;

    public record Audience(Long questionId, Set<String> direct, Set<String> mentioned) {
    }

    public Audience audience(CommunityOutboxEvent event, String worker) {
        Set<String> direct = new HashSet<>();
        Set<String> mentioned = new HashSet<>();
        event.getPayloadJson().path("recipientUserIds").forEach(value -> direct.add(value.asText()));
        Long question = null;
        if (event.getContentId() != null) {
            var content = repository.require(CommunityContent.class, event.getContentId());
            if (!Objects.equals(content.getOwnerOrgId(), event.getOwnerOrgId())) {
                throw new IllegalStateException("Event/source organization differs");
            }
            if ("answer".equals(content.getKind())) {
                question = repository.require(Answer.class, content.getContentId()).getQuestionContentId();
                direct.add(repository.require(CommunityContent.class, question).getAuthorUserId());
            }
            if (event.getEventType().startsWith("comment_")) {
                direct.add(content.getAuthorUserId());
                direct.add(content.getMaintainerUserId());
            }
            if ("content_published".equals(event.getEventType()) && content.getCurrentPublishedRevisionId() != null
                && event.getPayloadJson().path("revisionId").asLong() == content.getCurrentPublishedRevisionId()) {
                var revision = repository.require(ContentRevision.class, content.getCurrentPublishedRevisionId());
                var previous = repository.page("""
                    select r from ContentRevision r where r.contentId=:content and r.revisionNo<:number
                      and r.revisionStatus in ('published','superseded') order by r.revisionNo desc
                    """, ContentRevision.class, Map.of("content", content.getContentId(), "number", revision.getRevisionNo()), 0, 1);
                Set<String> old = previous.isEmpty() ? Set.of() : payload.mentions(previous.get(0).getBodyMarkdown());
                for (String user : payload.mentions(revision.getBodyMarkdown())) {
                    mention(user, revision.getRevisionId(), null, worker);
                    if (!old.contains(user)) {
                        direct.add(user);
                        mentioned.add(user);
                    }
                }
            }
            if (event.getPayloadJson().hasNonNull("commentId")) {
                var comment = repository.require(CommunityComment.class, event.getPayloadJson().get("commentId")
                    .asLong());
                if (comment.getDeletedAt() == null && "visible".equals(comment.getStatus())) {
                    for (String user : payload.mentions(comment.getBodyMarkdown())) {
                        if (mention(user, null, comment.getCommentId(), worker)) {
                            direct.add(user);
                            mentioned.add(user);
                        }
                    }
                    if (comment.getParentCommentId() != null) {
                        direct.add(repository.require(CommunityComment.class, comment.getParentCommentId())
                            .getAuthorUserId());
                    }
                }
            }
        }
        return new Audience(question, direct, mentioned);
    }

    public List<CommunityFanoutRepository.Recipient> deliver(CommunityOutboxEvent event, Audience audience,
                                                              String after, String worker) {
        var recipients = fanout.recipients(event, audience.questionId(), after, audience.direct());
        for (var recipient : recipients) {
            if (fanout.already(event.getEventId(), recipient.userId())) {
                continue;
            }
            var pref = repository.find(CommunityUserPreferences.class, recipient.userId());
            String key = audience.direct().contains(recipient.userId()) || event.getEventType().startsWith("help_")
                ? "reply" : "subscription";
            if (audience.mentioned().contains(recipient.userId())) {
                key = "mention";
            }
            if (event.getEventType().contains("invite")) {
                key = "invitation";
            }
            if (pref != null && pref.getNotificationPreferencesJson().has(key)
                && !pref.getNotificationPreferencesJson().path(key).asBoolean()
                && !"content_moderated".equals(event.getEventType())) {
                continue;
            }
            boolean digest = "digest".equals(recipient.mode()) && pref != null
                && !"off".equals(pref.getDigestFrequency());
            CommunityNotification notification = new CommunityNotification();
            notification.setEventId(event.getEventId());
            notification.setRecipientUserId(recipient.userId());
            notification.setContentId(event.getContentId());
            notification.setDeliveryMode(digest ? "digest" : "instant");
            notification.setDeliveryStatus(digest ? "pending" : "delivered");
            notification.setDeliveredAt(digest ? null : Instant.now());
            repository.save(notification, worker);
        }
        return recipients;
    }

    public void createDigestWindows(String worker) {
        for (var preference : fanout.digestDue()) {
            NotificationDigestBatch batch = new NotificationDigestBatch();
            batch.setUserId(preference.getUserId());
            Instant last = fanout.latestWindow(preference.getUserId());
            batch.setWindowStart(last == null ? Instant.EPOCH : last);
            batch.setWindowEnd(preference.getNextDigestAt());
            batch.setBatchStatus("pending");
            batch.setAttemptCount(0);
            repository.save(batch, worker);
            preference.setNextDigestAt(preferences.nextDigest(preference, batch.getWindowEnd()));
            repository.save(preference, worker);
        }
    }

    public List<CommunityNotification> claimDigestItems(NotificationDigestBatch batch, String worker) {
        var values = fanout.unclaimedDigest(batch.getUserId(), batch.getWindowStart(), batch.getWindowEnd());
        for (var notification : values) {
            NotificationDigestItem item = new NotificationDigestItem();
            item.setBatchId(batch.getBatchId());
            item.setNotificationId(notification.getNotificationId());
            repository.save(item, worker);
            boolean allowed = false;
            try {
                var actor = identity.actor(batch.getUserId());
                var event = repository.require(CommunityOutboxEvent.class, notification.getEventId());
                if (Objects.equals(actor.person().orgId(), event.getOwnerOrgId())
                    && notification.getContentId() != null) {
                    var content = repository.require(CommunityContent.class, notification.getContentId());
                    allowed = content.getCurrentPublishedRevisionId() != null && policy.canRead(content, actor);
                }
            } catch (BusinessException exception) {
                // Recipient khóa/chuyển org không làm retry vô hạn một batch; nguồn không được công bố lại.
                allowed = false;
            }
            notification.setDeliveryStatus(allowed ? "delivered" : "canceled");
            notification.setDeliveredAt(allowed ? Instant.now() : null);
            repository.save(notification, worker);
        }
        return values;
    }

    private boolean mention(String user, Long revision, Long comment, String worker) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("user", user);
        parameters.put("revision", revision);
        parameters.put("comment", comment);
        var count = repository.one("""
            select count(m) from CommunityMention m where m.mentionedUserId=:user
              and (m.revisionId=:revision or m.commentId=:comment)
            """, Long.class, parameters);
        if (count > 0) {
            return false;
        }
        CommunityMention mention = new CommunityMention();
        mention.setMentionedUserId(user);
        mention.setRevisionId(revision);
        mention.setCommentId(comment);
        repository.save(mention, worker);
        return true;
    }
}
