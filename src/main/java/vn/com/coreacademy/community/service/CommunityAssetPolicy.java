package vn.com.coreacademy.community.service;

import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.entity.CommunityAsset;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.entity.RevisionAsset;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityMediaRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.storage.dto.ObjectDownload;

@Component
@RequiredArgsConstructor
public class CommunityAssetPolicy {
    private final CommunityRepository repository;
    private final CommunityMediaRepository media;
    private final CommunityPolicy policy;

    public void requireOwn(CommunityAsset asset, Actor actor) {
        if (asset == null || !Objects.equals(asset.getUploadedBy(), actor.person().id())
            || !Objects.equals(asset.getOwnerOrgId(), actor.person().orgId()) || asset.getDeletedAt() != null) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        var usages = media.usages(asset.getAssetId());
        if (!usages.isEmpty() && usages.stream().noneMatch(usage -> readable(usage, actor))) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
    }

    public boolean readable(RevisionAsset usage, Actor actor) {
        var revision = repository.find(ContentRevision.class, usage.getRevisionId());
        if (revision == null || revision.getDeletedAt() != null) {
            return false;
        }
        var content = repository.find(CommunityContent.class, revision.getContentId());
        if (content == null || !policy.canRead(content, actor)) {
            return false;
        }
        return "published".equals(revision.getRevisionStatus()) || "superseded".equals(revision.getRevisionStatus())
            || policy.canEdit(content, actor);
    }

    public ObjectDownload download(Long revisionAssetId, Actor actor) {
        var usage = repository.require(RevisionAsset.class, revisionAssetId);
        if (!readable(usage, actor)) {
            throw new BusinessException(CommunityResponseCode.NOT_FOUND);
        }
        var asset = repository.require(CommunityAsset.class, usage.getAssetId());
        if (!"ready".equals(asset.getStatus()) || asset.getDeletedAt() != null || asset.getMediaCheckedAt() == null) {
            throw new BusinessException(CommunityResponseCode.ASSET_NOT_READY);
        }
        return asset.getPlaybackKey() == null
            ? new ObjectDownload(asset.getStorageKey(), asset.getMimeType(), asset.getOriginalName(),
                asset.getSizeBytes())
            : new ObjectDownload(asset.getPlaybackKey(), asset.getPlaybackMimeType(), asset.getOriginalName(),
                asset.getPlaybackSizeBytes());
    }
}
