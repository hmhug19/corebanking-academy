package vn.com.coreacademy.identity.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.identity.entity.RoleAssignment;

public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, Long> {
    /**
     * Lấy ADMIN ORG gán trực tiếp tại node, với tài khoản, vai trò và grant đang hoạt động. Không gộp GLOBAL
     * hoặc quản trị kế thừa; giới hạn số dòng do Pageable của bên gọi quyết định.
     */
    @Query("""
        select a from RoleAssignment a
        join fetch a.role
        join fetch a.user
        where a.active = true
          and a.role.active = true
          and a.user.status = ACTIVE
          and a.scopeType = ORG
          and a.orgId = :orgId
          and a.teamId is null
          and a.role.code = 'ADMIN'
        order by a.user.id
        """)
    List<RoleAssignment> findAdministratorsAtOrg(@Param("orgId") Long orgId, Pageable pageable);

    @Query("""
        select a from RoleAssignment a
        join fetch a.role
        where a.user.id in :userIds
          and a.active = true
          and a.role.active = true
        """)
    List<RoleAssignment> findActiveForUsers(@Param("userIds") Collection<String> userIds);

    boolean existsByTeamId(Long teamId);

    /**
     * Lấy grant SUPER_ADMIN GLOBAL hợp lệ của tài khoản active để bảo vệ tài khoản gốc cuối cùng.
     * Bên gọi phải flush thay đổi và giữ khóa ghi chung khi dùng kết quả để quyết định thao tác ghi.
     */
    @Query("""
        select a from RoleAssignment a
        join fetch a.role
        join fetch a.user
        where a.active = true
          and a.role.active = true
          and a.user.status = ACTIVE
          and a.scopeType = GLOBAL
          and a.orgId is null
          and a.teamId is null
          and a.role.code = 'SUPER_ADMIN'
        """)
    List<RoleAssignment> findEffectiveSuperAdministrators();

    /**
     * Lấy grant và role đang hoạt động; chưa kiểm tra cấu trúc scope, đích hoặc trạng thái tổ chức. Policy
     * phải kiểm tra hiệu lực đầy đủ trước khi dùng để cấp quyền.
     */
    @Query("""
        select a from RoleAssignment a
        join fetch a.role
        where a.user.id = :userId
          and a.active = true
          and a.role.active = true
        """)
    List<RoleAssignment> findActiveForUser(@Param("userId") String userId);
}
