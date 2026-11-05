package vn.com.coreacademy.identity.repository;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.identity.entity.AccessRequest;
import vn.com.coreacademy.identity.enums.AccessRequestKind;
import vn.com.coreacademy.identity.enums.AccessRequestStatus;

public interface AccessRequestRepository extends JpaRepository<AccessRequest, Long> {
    /**
     * Scope is cut here, before paging and counting: a global caller sees every row; anyone else only rows whose
     * unit is in {@code orgIds}, so a row with no unit stays hidden. {@code q} is a lower-cased LIKE pattern with
     * its wildcards already escaped by the caller.
     */
    @Query("""
        select r from AccessRequest r
        where (:global = true or r.orgId in :orgIds)
          and (:kind is null or r.kind = :kind)
          and (:status is null or r.status = :status)
          and (:q is null or lower(r.staffCode) like :q escape '\\'
               or lower(coalesce(r.fullName, '')) like :q escape '\\'
               or lower(coalesce(r.email, '')) like :q escape '\\')
        """)
    Page<AccessRequest> search(@Param("global") boolean global, @Param("orgIds") Collection<Long> orgIds,
        @Param("kind") AccessRequestKind kind, @Param("status") AccessRequestStatus status,
        @Param("q") String q, Pageable pageable);
}
