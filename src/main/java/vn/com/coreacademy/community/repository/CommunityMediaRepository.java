package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.community.entity.AssetUploadPart;
import vn.com.coreacademy.community.entity.CommunityAsset;
import vn.com.coreacademy.community.entity.RevisionAsset;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityMediaRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;

    public CommunityAsset upload(UUID id, String userId) {
        var values = repository.list("""
            select a from CommunityAsset a where a.uploadId = :upload and a.uploadedBy = :user
            """, CommunityAsset.class, Map.of("upload", id, "user", userId));
        return values.isEmpty() ? null : values.get(0);
    }

    public List<AssetUploadPart> parts(Long assetId) {
        return repository.list("""
            select p from CommunityAssetUploadPart p where p.assetId = :asset order by p.partNumber
            """, AssetUploadPart.class, Map.of("asset", assetId));
    }

    public List<RevisionAsset> usages(Long assetId) {
        return repository.list("select a from RevisionAsset a where a.assetId = :asset", RevisionAsset.class,
            Map.of("asset", assetId));
    }

    public List<CommunityAsset> pending() {
        return repository.page("""
            select a from CommunityAsset a where a.deletedAt is null and a.status in ('completing', 'processing')
              and (a.processingAvailableAt is null or a.processingAvailableAt <= :now)
              and (a.processingLeaseExpiresAt is null or a.processingLeaseExpiresAt <= :now)
            order by a.assetId
            """, CommunityAsset.class, Map.of("now", Instant.now()), 0, 1);
    }

    public List<CommunityAsset> expired() {
        return repository.page("""
            select a from CommunityAsset a where a.status = 'uploading' and a.uploadExpiresAt <= :now
            order by a.assetId
            """, CommunityAsset.class, Map.of("now", Instant.now()), 0, 100);
    }

    public boolean hasReferences(Long assetId) {
        return repository.one("select count(a) from RevisionAsset a where a.assetId = :asset", Long.class,
            Map.of("asset", assetId)) > 0;
    }

    public List<CommunityAsset> cleanupCandidates(Instant before) {
        return repository.page("""
            select a from CommunityAsset a where a.createTime < :before and a.cleanupStatus <> 'deleted'
              and a.status in ('ready', 'failed', 'aborted', 'expired') and a.cleanupAttempts < 3
              and not exists (select u.revisionAssetId from RevisionAsset u where u.assetId = a.assetId)
              and (a.cleanupAfter is null or a.cleanupAfter <= :now)
              and (a.cleanupLeaseExpiresAt is null or a.cleanupLeaseExpiresAt <= :now)
            order by a.assetId
            """, CommunityAsset.class, Map.of("before", before, "now", Instant.now()), 0, 1);
    }

    public Page<CommunityAsset> ownAssets(Actor actor, String status, String mediaType, String q, int page, int size) {
        var query = new NativeSearchQuery("from learning_hub.community_assets a\n");
        query.and("a.uploaded_by = :user and a.owner_org_id = :org and a.deleted_at is null");
        query.bind("user", actor.person().id());
        query.bind("org", actor.person().orgId());
        query.and("""
            not exists (select 1 from learning_hub.community_revision_assets ra where ra.asset_id = a.asset_id)
            or exists (select 1 from learning_hub.community_revision_assets ra
              join learning_hub.community_content_revisions r on r.revision_id = ra.revision_id
              join learning_hub.community_contents c on c.content_id=r.content_id
              where ra.asset_id = a.asset_id and r.deleted_at is null and r.content_id in (
            """ + CommunityVisibleSql.READABLE_IDS + """
            ) and (r.revision_status in ('published','superseded') or c.author_user_id=:visibleUser
              or c.maintainer_user_id=:visibleUser or exists(
                select 1 from learning_hub.community_content_access_grants ag where ag.content_id=c.content_id
                  and ag.status='active' and ag.permission='editor' and (ag.expires_at is null or ag.expires_at>:visibleNow)
                  and (ag.user_id=:visibleUser or exists(select 1 from learning_hub.community_guild_members gm
                    join learning_hub.community_guilds gg on gg.guild_id=gm.guild_id
                    where gm.guild_id=ag.guild_id and gm.user_id=:visibleUser and gm.status='active'
                      and gg.owner_org_id=:visibleOrg)))))
            """);
        CommunityVisibleSql.bind(query, actor);
        if (status != null) {
            query.and("a.status = :status");
            query.bind("status", status);
        }
        if (mediaType != null) {
            query.and("a.media_type = :type");
            query.bind("type", mediaType);
        }
        if (q != null && !q.isBlank()) {
            query.and("position(lower(:q) in lower(a.original_name)) > 0");
            query.bind("q", q.trim());
        }
        return query.page(entityManager, "select a.*\n", CommunityAsset.class,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id")), Map.of("id", "a.asset_id"));
    }
}
