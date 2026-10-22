package vn.com.coreacademy.knowledge.repository;

import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.knowledge.entity.DocumentStatus;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
    /** Cả kho dùng chung: mọi người đều thấy mọi tài liệu. */
    Page<KnowledgeDocument> findByStatusIn(Collection<DocumentStatus> statuses, Pageable pageable);

    /** Chỉ tài liệu của một người tải lên, dùng cho theo dõi tiến độ xử lý của chính họ. */
    Page<KnowledgeDocument> findByUploadedByAndStatusIn(String uploadedBy, Collection<DocumentStatus> statuses,
                                                        Pageable pageable);
}
