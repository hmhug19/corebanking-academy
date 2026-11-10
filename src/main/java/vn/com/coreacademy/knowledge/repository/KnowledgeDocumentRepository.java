package vn.com.coreacademy.knowledge.repository;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
}
