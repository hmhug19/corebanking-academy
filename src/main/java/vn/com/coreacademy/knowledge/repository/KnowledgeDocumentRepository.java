package vn.com.coreacademy.knowledge.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {
}
