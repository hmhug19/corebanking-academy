package vn.com.coreacademy.organization.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.common.persistence.IdCount;
import vn.com.coreacademy.organization.entity.Team;

public interface TeamRepository extends JpaRepository<Team, Long> {
    Page<Team> findByOrgId(Long orgId, Pageable pageable);

    long countByOrgId(Long orgId);

    @Query("""
        select t.orgId as id, count(t) as total
        from Team t
        where t.orgId in :ids
        group by t.orgId
        """)
    List<IdCount> countTeamsByOrgIds(@Param("ids") Collection<Long> ids);

    boolean existsByOrgIdAndCodeIgnoreCaseAndIdNot(Long orgId, String code, Long id);
}
