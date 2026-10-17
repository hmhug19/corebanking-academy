package vn.com.coreacademy.identity.repository.custom;

import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.identity.repository.projection.UserSearchRow;

@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserRepositoryCustomImpl implements UserRepositoryCustom {
    private static final Map<String, String> SORT_COLUMNS = Map.of(
        "id", "u.user_id", "username", "u.user_name", "fullName", "u.full_name", "createTime", "u.create_time");
    private final EntityManager entityManager;

    /**
     * Scope lọc user trước pagination/count. EXISTS lọc role không nhân số dòng khi user có nhiều grant;
     * ADMIN ORG chỉ lọc được grant mà policy cho hiển thị, không suy quyền từ profile org của grant target.
     */
    @Override
    public Page<UserSearchRow> searchUsers(UserSearch filter, Pageable pageable) {
        NativeSearchQuery sql = new NativeSearchQuery("""
            from learning_hub.users u
            """);

        if (!filter.global()) {
            sql.in("u.org_id", "visibleOrgIds", filter.visibleOrgIds());
        }
        if (filter.selectedOrgIds() != null) {
            sql.in("u.org_id", "selectedOrgIds", filter.selectedOrgIds());
        }

        if (filter.teamId() != null) {
            sql.and("u.team_id = :teamId");
            sql.bind("teamId", filter.teamId());
        }
        if (filter.status() != null) {
            sql.and("u.status = :status");
            sql.bind("status", filter.status().getValue());
        }

        String term = NativeSearchQuery.containsPattern(filter.q());
        if (term != null) {
            sql.and("""
                lower(u.user_id) like :term escape '!'
                or lower(u.user_name) like :term escape '!'
                or lower(u.full_name) like :term escape '!'
                or lower(u.email) like :term escape '!'
                """);
            sql.bind("term", term);
        }

        applyRoleFilter(sql, filter);

        return sql.page(entityManager, """
            select u.user_id, u.user_name, u.full_name, u.email, u.status, u.auth_provider,
                   u.must_change_password, u.org_id, u.team_id, u.position_name
            """, UserSearchRow.class, pageable, SORT_COLUMNS);
    }

    private void applyRoleFilter(NativeSearchQuery sql, UserSearch filter) {
        if (filter.roleCode() == null) {
            return;
        }

        String visibleGrant = "";
        if (!filter.global() && !filter.visibleOrgIds().isEmpty()) {
            visibleGrant = """
                and (
                    (g.scope = 'OWN' and g.org_id is null and g.team_id is null)
                    or (g.scope = 'ORG' and g.org_id in (:visibleOrgIds) and g.team_id is null)
                    or (g.scope = 'TEAM' and exists (
                        select 1 from learning_hub.teams t
                        where t.team_id = g.team_id
                          and t.org_id in (:visibleOrgIds)
                          and (g.org_id is null or g.org_id = t.org_id)
                    ))
                )
                """;
        }

        sql.and("""
            exists (
                select 1 from learning_hub.user_roles g
                join learning_hub.roles r on r.role_id = g.role_id
                where g.user_id = u.user_id
                  and g.active = true and r.active = true
                  and r.code = :roleCode
                %s
            )
            """.formatted(visibleGrant));
        sql.bind("roleCode", filter.roleCode());
    }
}
