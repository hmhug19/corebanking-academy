package vn.com.coreacademy.knowledge;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.coreacademy.knowledge.entity.DocumentStatus;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.NewChunk;
import vn.com.coreacademy.knowledge.repository.KnowledgeDocumentRepository;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real pgvector database: the context starts with ddl-auto=validate, so the entity matches the SQL. */
class KnowledgeSchemaIT extends KnowledgeIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private DocumentChunkRepository chunks;

    @Test
    void embeddingColumnHasAnHnswCosineIndex() {
        String definition = jdbc.queryForObject("""
            SELECT indexdef FROM pg_indexes
            WHERE schemaname = 'learning_hub' AND indexname = 'idx_knowledge_chunks_embedding'
            """, String.class);

        assertThat(definition).contains("USING hnsw").contains("vector_cosine_ops");
    }

    @Test
    void documentEntityRoundTripsWithItsStatus() {
        KnowledgeDocument saved = documents.saveAndFlush(newDocument());

        KnowledgeDocument loaded = documents.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getStatus()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(loaded.getCreateTime()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT status FROM learning_hub.knowledge_documents WHERE document_id = ?",
            String.class, saved.getId())).isEqualTo("queued");
    }

    @Test
    void chunkInsertAndCosineQueryReturnTheClosestVectorFirst() {
        Long documentId = documents.saveAndFlush(newDocument()).getId();
        chunks.insertAll(documentId, List.of(
            new NewChunk(0, "along x", null, 1, 3, 10, unit(0)),
            new NewChunk(1, "along y", 2, 4, 6, 12, unit(1))));

        List<String> ranked = jdbc.queryForList("""
            SELECT content FROM learning_hub.knowledge_document_chunks
            WHERE document_id = ?
            ORDER BY embedding <=> CAST(? AS vector)
            """, String.class, documentId, vectorOf(unit(1)));

        assertThat(ranked).containsExactly("along y", "along x");
    }

    @Test
    void aWrongSizedVectorIsRejectedByTheColumn() {
        Long documentId = documents.saveAndFlush(newDocument()).getId();
        float[] tooShort = new float[3];

        assertThatThrownBy(
            () -> chunks.insertAll(documentId, List.of(new NewChunk(0, "short", null, 1, 1, 1, tooShort))))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void everyRoleSeesTheAiAssistantFunction() {
        List<String> roles = jdbc.queryForList("""
            SELECT r.code FROM learning_hub.role_functions rf
            JOIN learning_hub.roles r ON r.role_id = rf.role_id
            JOIN learning_hub.functions f ON f.function_id = rf.function_id
            WHERE f.code = 'AI_ASSISTANT' AND f.route_pattern = '/assistant'
            ORDER BY r.code
            """, String.class);

        assertThat(roles).containsExactly("ADMIN", "INSTRUCTOR", "LEARNER", "REPORT_VIEWER", "SUPER_ADMIN");
    }

    private static KnowledgeDocument newDocument() {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setUploadedBy("000005");
        document.setFileName("quy-trinh.pdf");
        document.setMimeType("application/pdf");
        document.setSizeBytes(1234);
        document.setStorageKey("00000000-0000-0000-0000-000000000001");
        return document;
    }

    private static float[] unit(int axis) {
        float[] vector = new float[1024];
        vector[axis] = 1f;
        return vector;
    }

    private static String vectorOf(float[] vector) {
        return Arrays.toString(vector).replace(" ", "");
    }
}
