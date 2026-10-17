package vn.com.coreacademy.organization.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.repository.custom.OrganizationRepositoryCustom;

public interface OrganizationRepository extends JpaRepository<Organization, Long>, OrganizationRepositoryCustom {
    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

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
