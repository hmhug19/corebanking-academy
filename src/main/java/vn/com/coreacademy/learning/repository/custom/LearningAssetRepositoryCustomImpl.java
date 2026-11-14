package vn.com.coreacademy.learning.repository.custom;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.learning.constants.LearningCodes.EnrollmentOrigin;
import vn.com.coreacademy.learning.dto.MediaViews;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LearningAssetRepositoryCustomImpl implements LearningAssetRepositoryCustom {
    private final EntityManager entityManager;

    @Override
    public Page<MediaViews.Asset> search(Filter filter, Pageable page) {
        NativeSearchQuery sql = new NativeSearchQuery("from learning_hub.assets a\n");
        sql.and("not a.is_deleted");
        if (!filter.scope().global()) {
            // Asset không có team owner; TEAM chỉ liệt kê file do caller upload, không suy scope toàn đơn vị.
            sql.and("""
                a.owner_org_id in (:orgs)
                or (a.uploaded_by = :actor and a.owner_org_id = :org and :self)
                """);
            sql.bind("orgs", filter.scope().orgIds().isEmpty() ? List.of(-1L) : filter.scope().orgIds());
            sql.bind("actor", filter.actorId());
            sql.bind("org", filter.orgId() == null ? -1L : filter.orgId());
            sql.bind(EnrollmentOrigin.SELF, filter.scope().own()
                || (filter.teamId() != null && filter.scope().teamIds().contains(filter.teamId())));
        }
        String term = NativeSearchQuery.containsPattern(filter.q());
        if (term != null) {
            sql.and("""
                (lower(a.original_name) like :term escape '!'
                  or lower(a.display_name) like :term escape '!'
                  or lower(a.description) like :term escape '!')
                """);
            sql.bind("term", term);
        }
        if (filter.mediaType() != null) {
            sql.and("a.media_type = :type");
            sql.bind("type", filter.mediaType());
        }
        if (filter.checksum() != null) {
            sql.and("a.owner_org_id = :owner and a.checksum_sha256 = :checksum and a.size_bytes = :bytes");
            sql.bind("owner", filter.ownerOrgFilter());
            sql.bind("checksum", filter.checksum());
            sql.bind("bytes", filter.sizeBytes());
        }
        if (filter.status() != null) {
            sql.and(filter.includeDeleting() && "ready".equals(filter.status())
                ? "(a.status = :status or a.status = 'deleting')" : "a.status = :status");
            sql.bind("status", filter.status());
        }
        return sql.page(entityManager, """
            select a.asset_id, a.owner_org_id, a.original_name, a.mime_type, a.size_bytes,
                   a.media_type, a.status, a.create_time, coalesce(a.display_name, a.original_name), a.description
            """, MediaViews.Asset.class, page, Map.of("id", "a.asset_id", "name", "coalesce(a.display_name, a.original_name)",
                "createTime", "a.create_time"));
    }
}
