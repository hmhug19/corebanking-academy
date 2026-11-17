package vn.com.coreacademy.learning.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.learning.constants.LearningCodes.AssetStatus;
import vn.com.coreacademy.learning.entity.AssetUploadPart;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.entity.LearningUserLease;

/** Query theo khóa nghiệp vụ; bên gọi giữ LearningWriteLock cho mọi thao tác đổi trạng thái. */
@Repository
@RequiredArgsConstructor
public class LearningMediaRepository {
    private final EntityManager entityManager;

    public LearningAsset upload(UUID uploadId) {
        return entityManager.createQuery("""
            select a from LearningAsset a where a.uploadId = :id
            """, LearningAsset.class).setParameter("id", uploadId).getResultList().stream().findFirst().orElse(null);
    }

    public List<AssetUploadPart> parts(UUID uploadId) {
        return entityManager.createQuery("""
            select p from AssetUploadPart p where p.uploadId = :id order by p.partNumber
            """, AssetUploadPart.class).setParameter("id", uploadId).getResultList();
    }

    public LearningUserLease lease(String userId) {
        return entityManager.find(LearningUserLease.class, userId);
    }

    public boolean referenced(Long assetId) {
        return Boolean.TRUE.equals(entityManager.createNativeQuery("""
            select exists (select 1 from learning_hub.block_assets where asset_id = :id)
                or exists (select 1 from learning_hub.lesson_blocks where media_asset_id = :id)
                or exists (select 1 from learning_hub.course_revisions where cover_asset_id = :id)
                or exists (select 1 from learning_hub.block_progress where media_asset_id = :id)
            """).setParameter("id", assetId).getSingleResult());
    }

    /** Không xóa object nếu metadata của asset khác còn trỏ tới cùng key. */
    public boolean sharedObject(Long assetId, String key) {
        return Boolean.TRUE.equals(entityManager.createQuery("""
            select count(a) > 0 from LearningAsset a
            where a.id <> :id and (a.storageKey = :key or a.playbackKey = :key)
            """, Boolean.class).setParameter("id", assetId).setParameter("key", key).getSingleResult());
    }

    /** Chỉ đánh dấu metadata hết hạn; không abort hoặc xóa object trên S3. */
    public void expireUploads() {
        entityManager.createNativeQuery("""
            update learning_hub.assets
            set status = :expired, row_version = row_version + 1,
                update_time = current_timestamp, updated_by = uploaded_by
            where status = :uploading and upload_expires_at <= current_timestamp
            """).setParameter("expired", AssetStatus.EXPIRED)
            .setParameter("uploading", AssetStatus.UPLOADING).executeUpdate();
    }

    /**
     * Records how far the claim `claim` is, null to clear it. Bypasses the entity version on purpose: the
     * heartbeat and the result write of the same claim must not conflict with a progress update.
     */
    public void reportProgress(Long assetId, UUID claim, Integer percent) {
        // Two statements: a null bind has no SQL type for PostgreSQL to coerce into integer.
        var query = percent == null
            ? entityManager.createNativeQuery("""
                update learning_hub.assets set processing_progress = null
                where id = :id and processing_lease_id = :claim
                """)
            : entityManager.createNativeQuery("""
                update learning_hub.assets set processing_progress = :percent
                where id = :id and processing_lease_id = :claim
                """).setParameter("percent", percent);
        query.setParameter("id", assetId).setParameter("claim", claim).executeUpdate();
    }

    public List<Long> pending() {
        return entityManager.createQuery("""
            select a.id from LearningAsset a
            where a.status in (:completing, :processing) and a.isDeleted = false
              and (a.processingLeaseExpiresAt is null or a.processingLeaseExpiresAt < :now)
            order by a.id
            """, Long.class).setParameter("now", Instant.now())
            .setParameter("completing", AssetStatus.COMPLETING)
            .setParameter("processing", AssetStatus.PROCESSING).setMaxResults(1).getResultList();
    }

}
