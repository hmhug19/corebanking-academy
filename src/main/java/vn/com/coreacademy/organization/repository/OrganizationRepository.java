package vn.com.coreacademy.organization.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.repository.custom.OrganizationRepositoryCustom;

public interface OrganizationRepository extends JpaRepository<Organization, Long>, OrganizationRepositoryCustom {
    @Query(value = """
        with recursive ancestors(org_id, parent_id) as (
            select org_id, parent_id from learning_hub.orgs
            where org_id = :id and status = 'active'
            union
            select o.org_id, o.parent_id from learning_hub.orgs o
            join ancestors a on o.org_id = a.parent_id
            where o.status = 'active'
        )
        select org_id from ancestors
        """, nativeQuery = true)
    List<Long> findActiveAncestorIds(@Param("id") Long id);

    List<Organization> findByOrgTypeAndStatusOrderBySortNoAscNameAsc(String orgType, String status);

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    @Query("""
        select o
        from Organization o
        where lower(o.code) in :codes
        """)
    List<Organization> findByNormalizedCodes(@Param("codes") Collection<String> codes);

    /** Kiểm tra đường cha cho cả lô tham chiếu; UNION chặn chu trình, không query từng dòng import. */
    @Query(value = """
        with recursive ancestors(root_id, org_id, parent_id, status) as (
            select org_id, org_id, parent_id, status
            from learning_hub.orgs
            where org_id in :ids
            union
            select a.root_id, o.org_id, o.parent_id, o.status
            from learning_hub.orgs o
            join ancestors a on o.org_id = a.parent_id
        )
        select root_id
        from ancestors
        group by root_id
        having bool_and(status = 'active')
            and count(*) filter (where parent_id is null) = 1
        """, nativeQuery = true)
    List<Long> findIdsWithActiveAncestry(@Param("ids") Collection<Long> ids);

    @Query("""
        select distinct o.parentId
        from Organization o
        where o.parentId in :parentIds
        """)
    List<Long> findParentIdsWithChildren(@Param("parentIds") Collection<Long> parentIds);

    /**
     * Chỉ dò con thuộc scope để hasChildren không tiết lộ nhánh tổ chức ngoài quyền.
     */
    @Query("""
        select distinct o.parentId
        from Organization o
        where o.parentId in :parentIds
            and o.id in :visibleOrgIds
        """)
    List<Long> findParentIdsWithVisibleChildren(@Param("parentIds") Collection<Long> parentIds,
                                                @Param("visibleOrgIds") Collection<Long> visibleOrgIds);

    /**
     * Lấy node active được chọn và cây con nối qua các node active. UNION loại bản ghi lặp để đệ quy kết thúc
     * khi dữ liệu có chu trình; hàm này không kiểm tra đường cha của node gốc, bên gọi cần kiểm tra riêng khi
     * cấp quyền.
     */
    @Query(value = """
        with recursive subtree(org_id) as (
            select org_id
            from learning_hub.orgs
            where org_id = :id and status = 'active'
            union
            select o.org_id
            from learning_hub.orgs o
            join subtree s on o.parent_id = s.org_id
            where o.status = 'active'
        )
        select org_id from subtree
        """, nativeQuery = true)
    List<Long> findActiveDescendantIds(@Param("id") Long id);

    /**
     * Chỉ chấp nhận node tồn tại có toàn bộ đường cha active và đi tới đúng một gốc parent_id=null. UNION chặn
     * lặp khi có chu trình; đường cha đứt hoặc chu trình không tới gốc không cấp quyền.
     */
    @Query(value = """
        with recursive ancestors(org_id, parent_id, status) as (
            select org_id, parent_id, status
            from learning_hub.orgs
            where org_id = :id
            union
            select o.org_id, o.parent_id, o.status
            from learning_hub.orgs o
            join ancestors a on o.org_id = a.parent_id
        )
        select count(*) > 0 and bool_and(status = 'active')
           and count(*) filter (where parent_id is null) = 1
        from ancestors
        """, nativeQuery = true)
    boolean hasActiveAncestry(@Param("id") Long id);
}
