package vn.com.coreacademy.organization.repository.custom;

import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.organization.entity.Organization;

@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrganizationRepositoryCustomImpl implements OrganizationRepositoryCustom {
    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "id", "o.org_id", "code", "o.code", "name", "o.name", "sortNo", "o.sort_no");
    private final EntityManager entityManager;

    /** Gốc của ADMIN ORG có thể có cha ngoài scope; list/count dùng cùng điều kiện để không lộ cây ngoài quyền. */
    @Override
    public Page<Organization> searchOrganizations(OrganizationSearch filter, Pageable pageable) {
        NativeSearchQuery sql = new NativeSearchQuery("""
            from learning_hub.orgs o
            """);

        if (!filter.global()) {
            sql.in("o.org_id", "visibleOrgIds", filter.visibleOrgIds());
        }

        if (filter.parentId() != null) {
            sql.and("o.parent_id = :parentId");
            sql.bind("parentId", filter.parentId());
        } else if (filter.rootOnly()) {
            if (filter.global()) {
                sql.and("o.parent_id is null");
            } else if (!filter.visibleOrgIds().isEmpty()) {
                sql.and("o.parent_id is null or o.parent_id not in (:visibleOrgIds)");
            }
        }

        if (filter.orgType() != null) {
            sql.and("o.org_type = :orgType");
            sql.bind("orgType", filter.orgType());
        }
        if (filter.status() != null) {
            sql.and("o.status = :status");
            sql.bind("status", filter.status());
        }

        String term = NativeSearchQuery.containsPattern(filter.q());
        if (term != null) {
            sql.and("lower(o.code) like :term escape '!' or lower(o.name) like :term escape '!'");
            sql.bind("term", term);
        }

        return sql.page(entityManager, """
            select o.org_id, o.parent_id, o.org_type, o.code, o.name, o.status, o.sort_no,
                   o.create_time, o.update_time
            """, Organization.class, pageable, SORT_COLUMNS);
    }
}
