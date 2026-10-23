package vn.com.coreacademy.knowledge;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.NewChunk;
import vn.com.coreacademy.knowledge.repository.KnowledgeDocumentRepository;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DocumentChunkSearchIT extends KnowledgeIntegrationTest {
    @Autowired
    private DocumentChunkRepository chunks;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyKnowledgeBase() {
        jdbc.update("DELETE FROM learning_hub.knowledge_document_chunks");
        jdbc.update("DELETE FROM learning_hub.knowledge_documents");
    }

    @Test
    void returnsChunksInCosineOrderWithSimilarityAndFileName() {
        long first = document("quy-trinh.pdf");
        long second = document("checklist.md");
        chunks.insertAll(first, List.of(
            new NewChunk(0, "along x", 3, 5, 9, 10, axis(0)),
            new NewChunk(1, "between x and y", 3, 10, 12, 10, blend(0, 1))));
        chunks.insertAll(second, List.of(new NewChunk(0, "along z", null, 1, 4, 10, axis(2))));

        List<ChunkHit> hits = chunks.search(axis(0), 3);

        assertThat(hits).extracting(ChunkHit::content).containsExactly("along x", "between x and y", "along z");
        assertThat(hits.get(0).similarity()).isCloseTo(1.0, within(1e-6));
        assertThat(hits.get(1).similarity()).isCloseTo(Math.sqrt(0.5), within(1e-4));
        assertThat(hits.get(2).similarity()).isCloseTo(0.0, within(1e-6));
        assertThat(hits.get(0)).extracting(ChunkHit::fileName, ChunkHit::page, ChunkHit::lineStart, ChunkHit::lineEnd,
            ChunkHit::documentId).containsExactly("quy-trinh.pdf", 3, 5, 9, first);
        assertThat(hits.get(2).page()).isNull();
        assertThat(hits.get(2).fileName()).isEqualTo("checklist.md");
    }

    @Test
    void respectsTheLimit() {
        long id = document("a.md");
        chunks.insertAll(id, List.of(
            new NewChunk(0, "one", null, 1, 1, 1, axis(0)),
            new NewChunk(1, "two", null, 2, 2, 1, axis(1)),
            new NewChunk(2, "three", null, 3, 3, 1, axis(2))));

        assertThat(chunks.search(axis(0), 2)).hasSize(2);
    }

    @Test
    void anEmptyKnowledgeBaseReturnsNothing() {
        assertThat(chunks.search(axis(0), 5)).isEmpty();
    }

    private long document(String fileName) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setUploadedBy("000005");
        document.setFileName(fileName);
        document.setMimeType("application/pdf");
        document.setSizeBytes(1);
        document.setStorageKey("00000000-0000-0000-0000-000000000001");
        return documents.saveAndFlush(document).getId();
    }

    private static float[] axis(int index) {
        float[] vector = new float[1024];
        vector[index] = 1f;
        return vector;
    }

    private static float[] blend(int a, int b) {
        float[] vector = new float[1024];
        vector[a] = (float) Math.sqrt(0.5);
        vector[b] = (float) Math.sqrt(0.5);
        return vector;
    }
}
