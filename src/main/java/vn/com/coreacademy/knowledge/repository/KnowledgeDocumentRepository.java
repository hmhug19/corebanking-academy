package vn.com.coreacademy.knowledge.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.knowledge.entity.DocumentScope;
import vn.com.coreacademy.knowledge.entity.DocumentStatus;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    /** Tài liệu một người được đọc: của chính họ, của đơn vị họ (và đơn vị cha) và tài liệu toàn hệ thống. */
    @Query("""
        select d from KnowledgeDocument d
        where d.status in :statuses
          and (d.scope = vn.com.coreacademy.knowledge.entity.DocumentScope.GLOBAL
            or (d.scope = vn.com.coreacademy.knowledge.entity.DocumentScope.PERSONAL and d.uploadedBy = :userId)
            or (d.scope = vn.com.coreacademy.knowledge.entity.DocumentScope.UNIT and d.scopeOrgId in :orgIds))
        """)
    Page<KnowledgeDocument> findReadable(@Param("statuses") Collection<DocumentStatus> statuses,
                                         @Param("userId") String userId, @Param("orgIds") Collection<Long> orgIds,
                                         Pageable pageable);

    /** Chỉ tài liệu của một người tải lên, dùng cho theo dõi tiến độ xử lý của chính họ. */
    Page<KnowledgeDocument> findByUploadedByAndStatusIn(String uploadedBy, Collection<DocumentStatus> statuses,
                                                        Pageable pageable);

    /** The caller's own earlier upload of the same bytes in the same place; a failed one does not count. */
    Optional<KnowledgeDocument> findFirstByUploadedByAndScopeAndScopeOrgIdAndChecksumSha256AndStatusNotOrderByIdAsc(
        String uploadedBy, DocumentScope scope, Long scopeOrgId, String checksumSha256, DocumentStatus excluded);

    /** The caller's documents that share their checksum with another of the caller's in the same scope, newest first. */
    @Query("""
        select d from KnowledgeDocument d
        where d.uploadedBy = :userId and d.scope <> vn.com.coreacademy.knowledge.entity.DocumentScope.COURSE_SOURCE
          and d.checksumSha256 is not null
          and exists (select 1 from KnowledgeDocument o
                      where o.uploadedBy = d.uploadedBy and o.scope = d.scope
                        and coalesce(o.scopeOrgId, 0) = coalesce(d.scopeOrgId, 0)
                        and o.checksumSha256 = d.checksumSha256 and o.id <> d.id)
        order by d.checksumSha256, d.id desc
        """)
    List<KnowledgeDocument> findDuplicates(@Param("userId") String userId);
}
