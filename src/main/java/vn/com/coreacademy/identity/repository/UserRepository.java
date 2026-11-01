package vn.com.coreacademy.identity.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.common.persistence.IdCount;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.repository.custom.UserRepositoryCustom;
import vn.com.coreacademy.identity.repository.projection.LearningPersonRow;
import vn.com.coreacademy.identity.repository.projection.TeamMemberPreview;

public interface UserRepository extends JpaRepository<UserAccount, String>, UserRepositoryCustom {
    /**
     * Chỉ resolve học viên active có LEARNER/OWN hợp lệ, giới hạn tại DB trước khi giao đồng bộ. kind = 'all' lấy mọi
     * học viên đủ điều kiện, dùng cho bảng xếp hạng; giao học không dùng kind này.
     */
    @Query("""
        select u.id as id, u.fullName as fullName, u.orgId as orgId, u.teamId as teamId
        from UserAccount u
        where u.status = ACTIVE and u.mustChangePassword = false
          and ((:kind = 'user' and u.id in :userIds)
            or (:kind = 'org' and u.orgId in :orgIds)
            or (:kind = 'team' and u.teamId = :teamId and u.orgId in :orgIds)
            or :kind = 'all')
          and exists (
            select g.id from RoleAssignment g
            where g.user.id = u.id and g.active = true and g.role.active = true
              and g.role.code = 'LEARNER' and g.scopeType = OWN
              and g.orgId is null and g.teamId is null
          )
        order by u.id
        """)
    List<LearningPersonRow> findLearningCandidates(@Param("kind") String kind,
        @Param("userIds") Collection<String> userIds, @Param("orgIds") Collection<Long> orgIds,
        @Param("teamId") Long teamId, Pageable pageable);

    long countByOrgId(Long orgId);

    long countByTeamIdAndOrgId(Long teamId, Long orgId);

    @Query("""
        select u.orgId as id, count(u) as total
        from UserAccount u
        where u.orgId in :ids
        group by u.orgId
        """)
    List<IdCount> countMembersByOrgIds(@Param("ids") Collection<Long> ids);

    /**
     * Đếm theo lô thành viên có orgId khớp tổ chức thực tế của nhóm; không đưa hồ sơ lệch tổ chức vào số lượng
     * hiển thị.
     */
    @Query("""
        select u.teamId as id, count(u) as total
        from UserAccount u, Team t
        where u.teamId = t.id
          and u.orgId = t.orgId
          and t.id in :ids
        group by u.teamId
        """)
    List<IdCount> countMembersByTeamIds(@Param("ids") Collection<Long> ids);

    /**
     * Lấy tối đa 5 thành viên mỗi nhóm bằng row_number theo team_id, trong một query cho cả trang. Chỉ nhận hồ
     * sơ có org_id khớp nhóm và sắp theo user_id để kết quả xem trước ổn định.
     */
    @Query(value = """
        select p.team_id as teamId, p.user_id as userId, p.full_name as fullName
        from (
            select u.team_id, u.user_id, u.full_name,
                   row_number() over (partition by u.team_id order by u.user_id) as rn
            from learning_hub.users u
            join learning_hub.teams t on t.team_id = u.team_id
                and t.org_id = u.org_id
            where u.team_id in :ids
        ) p
        where p.rn <= 5
        order by p.team_id, p.user_id
        """, nativeQuery = true)
    List<TeamMemberPreview> findMemberPreviews(@Param("ids") Collection<Long> ids);

    boolean existsByTeamId(Long teamId);

    List<UserAccount> findTop5ByTeamIdAndOrgIdOrderById(Long teamId, Long orgId);

    /**
     * Kiểm tra trùng chéo mã cán bộ và tên đăng nhập không phân biệt hoa/thường trước khi tạo. Bên gọi phối
     * hợp IdentityWriteLock để tránh hai yêu cầu ghi cùng vượt kiểm tra.
     */
    @Query("""
        select count(u) > 0
        from UserAccount u
        where lower(u.id) = lower(:identifier)
           or lower(u.username) = lower(:identifier)
        """)
    boolean identifierExists(@Param("identifier") String identifier);

    Optional<UserAccount> findByUsername(String username);

    /**
     * Tăng auth_ver nếu phiên bản hiện tại khớp token, trả số dòng cập nhật để bên gọi phát hiện token cũ. Cập
     * nhật native tự ghi update_time vì không đi qua JPA lifecycle; yêu cầu transaction ghi.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
        update learning_hub.users
        set auth_ver = auth_ver + 1, update_time = CURRENT_TIMESTAMP
        where user_id = :userId
          and auth_ver = :expectedVersion
        """, nativeQuery = true)
    int revokeTokens(@Param("userId") String userId, @Param("expectedVersion") long expectedVersion);

    /**
     * Tìm tất cả ứng viên theo mã cán bộ hoặc username đã chuẩn hóa; bên gọi phải từ chối nếu kết quả không
     * duy nhất.
     */
    @Query("""
        select u from UserAccount u
        where u.id = :identifier
           or lower(u.username) = :normalizedUsername
        """)
    List<UserAccount> findLoginCandidates(@Param("identifier") String identifier,
                                          @Param("normalizedUsername") String normalizedUsername);
}
