package vn.com.coreacademy.community.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.DocumentRequests.CreateRecording;
import vn.com.coreacademy.community.dto.NotificationDtos.Retry;
import vn.com.coreacademy.community.dto.RecordingDtos.CreateTranscript;
import vn.com.coreacademy.community.dto.RecordingDtos.RecordingCard;
import vn.com.coreacademy.community.dto.RecordingDtos.Segment;
import vn.com.coreacademy.community.dto.RecordingDtos.TranscriptView;
import vn.com.coreacademy.community.dto.RecordingDtos.UpdateTranscript;
import vn.com.coreacademy.community.entity.CommunityAsset;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.MediaTranscript;
import vn.com.coreacademy.community.entity.RevisionAsset;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityMediaRepository;
import vn.com.coreacademy.community.repository.CommunityRelations;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.CommunityTranscriptRepository;
import vn.com.coreacademy.community.repository.ContentRepository;
import vn.com.coreacademy.community.repository.ContentRepository.DocumentFilter;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityRecordingService {
    private final CommunityRepository repository;
    private final CommunityMediaRepository media;
    private final CommunityTranscriptRepository transcripts;
    private final CommunityRelations relations;
    private final CommunityPolicy policy;
    private final CommunityAssetPolicy assets;
    private final CommunityContentService contents;
    private final CommunityGuildService guilds;
    private final CommunityPayload payload;
    private final ObjectMapper json;
    private final IdentityWriteLock writeLock;

    @Transactional
    public ContentDetail create(CreateRecording request) {
        return contents.create("recording", null, request.content(request.primaryGuildId()),
            payload.hash("recording", request));
    }

    public PageResponse<RecordingCard> list(String q, List<Long> tagIds, Long guildId, String speaker,
                                           Instant heldFrom, Instant heldTo, int page, int size) {
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        if (heldFrom != null && heldTo != null && !heldFrom.isBefore(heldTo)
            || speaker != null && speaker.length() > 255) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        return CommunityPages.map(contents.list(new ContentRepository.Search("recording", q, tagIds, null, null,
            false, null, null, page, size, "publishedAt", guildId,
            new DocumentFilter(null, false, null, null, null, null, speaker, heldFrom, heldTo, null, false))), card -> {
                var revision = repository.require(ContentRevision.class, card.publishedRevisionId());
                var context = revision.getContextJson().path("recording");
                var video = relations.assets(revision.getRevisionId()).stream()
                    .filter(value -> "primary_video".equals(value.getUsage())).findFirst().orElse(null);
                var asset = video == null ? null : repository.require(CommunityAsset.class, video.getAssetId());
                return new RecordingCard(card, context.hasNonNull("heldAt")
                    ? Instant.parse(context.get("heldAt").asText()) : null, context.path("speakers"),
                    asset == null ? null : asset.getDurationSeconds(), video == null ? null
                        : video.getRevisionAssetId(),
                    asset != null && !transcripts.published(asset.getAssetId()).isEmpty());
            });
    }

    @Transactional
    public TranscriptView createTranscript(Long assetId, CreateTranscript request) {
        writeLock.acquire();
        var actor = policy.actor();
        var asset = publicationRights(assetId, actor);
        String hash = payload.hash("transcript:" + assetId, request);
        var previous = transcripts.byRequest(actor.person().id(), request.clientRequestId());
        if (!previous.isEmpty()) {
            var value = previous.get(0);
            if (!Objects.equals(value.getAssetId(), assetId) || !hash.equals(value.getRequestHash())) {
                throw new BusinessException(CommunityResponseCode.IDEMPOTENCY_CONFLICT);
            }
            return view(value, true);
        }
        validateText(asset, request.bodyText(), request.segments());
        MediaTranscript value = new MediaTranscript();
        value.setAssetId(assetId);
        value.setVersionNo(transcripts.nextVersion(assetId));
        value.setLanguage(request.language());
        value.setBodyText(normalize(request.bodyText()));
        value.setSegmentsJson(segmentPayload(request.segments()));
        value.setStatus("draft");
        value.setClientRequestId(request.clientRequestId());
        value.setRequestHash(hash);
        repository.save(value, actor.person().id());
        repository.flush();
        return view(value, true);
    }

    @Transactional
    public TranscriptView updateTranscript(Long transcriptId, UpdateTranscript request) {
        writeLock.acquire();
        var actor = policy.actor();
        var value = repository.require(MediaTranscript.class, transcriptId);
        var asset = publicationRights(value.getAssetId(), actor);
        policy.version(value, request.expectedVersion());
        if (!"draft".equals(value.getStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        validateText(asset, request.bodyText(), request.segments());
        value.setLanguage(request.language());
        value.setBodyText(normalize(request.bodyText()));
        value.setSegmentsJson(segmentPayload(request.segments()));
        repository.save(value, actor.person().id());
        repository.flush();
        return view(value, true);
    }

    @Transactional
    public TranscriptView publishTranscript(Long transcriptId, Retry request) {
        writeLock.acquire();
        var actor = policy.actor();
        var value = repository.require(MediaTranscript.class, transcriptId);
        var asset = publicationRights(value.getAssetId(), actor);
        if ("published".equals(value.getStatus())) {
            return view(value, true);
        }
        policy.version(value, request.expectedVersion());
        if (!"draft".equals(value.getStatus())) {
            throw new BusinessException(CommunityResponseCode.INVALID_STATE);
        }
        validateText(asset, value.getBodyText(), segments(value));
        for (var old : transcripts.published(value.getAssetId())) {
            old.setStatus("superseded");
            repository.save(old, actor.person().id());
        }
        value.setStatus("published");
        value.setPublishedAt(Instant.now());
        repository.save(value, actor.person().id());
        // Mỗi root đang dùng current revision có sự kiện riêng; không sửa Markdown hoặc lịch sử revision.
        media.usages(value.getAssetId()).stream().map(RevisionAsset::getRevisionId).distinct().forEach(id -> {
            var revision = repository.require(ContentRevision.class, id);
            var content = repository.require(CommunityContent.class, revision.getContentId());
            if (Objects.equals(content.getCurrentPublishedRevisionId(), id)) {
                contents.event(content, "transcript_published", actor.person().id());
            }
        });
        repository.flush();
        return view(value, true);
    }

    public TranscriptView transcript(Long revisionAssetId) {
        var actor = policy.actor();
        var usage = repository.require(RevisionAsset.class, revisionAssetId);
        if (!assets.readable(usage, actor)) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        var asset = repository.require(CommunityAsset.class, usage.getAssetId());
        if (!"ready".equals(asset.getStatus()) || asset.getDeletedAt() != null) {
            throw new BusinessException(CommunityResponseCode.ASSET_NOT_READY);
        }
        var values = transcripts.published(asset.getAssetId());
        return values.isEmpty() ? null : view(values.get(0), false);
    }

    private CommunityAsset publicationRights(Long assetId, Actor actor) {
        var asset = repository.require(CommunityAsset.class, assetId);
        if (!Objects.equals(asset.getOwnerOrgId(), actor.person().orgId()) || asset.getDeletedAt() != null
            || !"ready".equals(asset.getStatus()) || !"video".equals(asset.getMediaType())
            || asset.getDurationSeconds() == null) {
            throw new BusinessException(CommunityResponseCode.ASSET_NOT_READY);
        }
        var usages = media.usages(assetId);
        if (usages.isEmpty()) {
            if (!Objects.equals(asset.getUploadedBy(), actor.person().id())) {
                throw new AccessDeniedException("Unattached video uploader required");
            }
        } else {
            for (var usage : usages) {
                var revision = repository.require(ContentRevision.class, usage.getRevisionId());
                var content = policy.readable(revision.getContentId(), actor);
                policy.requirePublisher(content, actor);
            }
        }
        return asset;
    }

    private void validateText(CommunityAsset asset, String body, List<Segment> segments) {
        BigDecimal end = BigDecimal.ZERO;
        long total = 0;
        for (var segment : segments) {
            total += segment.text().length();
            if (total > 1_000_000 || segment.startSeconds().compareTo(end) < 0
                || segment.endSeconds().compareTo(segment.startSeconds()) <= 0
                || segment.endSeconds().compareTo(asset.getDurationSeconds()) > 0) {
                throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
            }
            end = segment.endSeconds();
        }
        String joined = segments.stream().map(value -> normalize(value.text())).collect(Collectors.joining("\n"));
        if (!normalize(body).equals(joined)) {
            throw BusinessException.validation("bodyText", "SEGMENTS_MISMATCH", "Body phải khớp nội dung các segment.");
        }
    }

    private String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private List<Segment> segments(MediaTranscript value) {
        return json.convertValue(value.getSegmentsJson(),
            json.getTypeFactory().constructCollectionType(List.class, Segment.class));
    }

    private JsonNode segmentPayload(List<Segment> segments) {
        try {
            // valueToTree giữ DecimalNode của BigDecimal, còn snapshot JSON của Hibernate đọc lại thành
            // IntNode/DoubleNode. Chuẩn hóa qua JSON để không dirty giả và tăng version sau response create/update.
            return json.readTree(json.writeValueAsBytes(segments));
        } catch (IOException exception) {
            throw new IllegalStateException("Transcript segment serialization failed", exception);
        }
    }

    private TranscriptView view(MediaTranscript value, boolean manage) {
        return new TranscriptView(value.getTranscriptId(), value.getAssetId(), value.getVersionNo(),
            value.getLanguage(),
            value.getBodyText(), segments(value), value.getStatus(), value.getPublishedAt(), value.getRowVersion(),
            value.getCreateTime(), value.getUpdateTime(), manage && "draft".equals(value.getStatus())
                ? List.of("edit", "publish") : List.of());
    }
}
