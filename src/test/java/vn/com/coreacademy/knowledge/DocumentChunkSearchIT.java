package vn.com.coreacademy.knowledge;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.coreacademy.knowledge.config.EmbeddingDimensionCheck;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.entity.DocumentScope;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.NewChunk;
import vn.com.coreacademy.knowledge.repository.DocumentVisibility;
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

    @Autowired
    private KnowledgeProperties properties;

    private long unit;
    private long child;
    private long sibling;

    @BeforeEach
    void emptyKnowledgeBase() {
        unit = org("SCOPE-U", null);
        child = org("SCOPE-C", unit);
        sibling = org("SCOPE-S", null);
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

        List<ChunkHit> hits = chunks.search(axis(0), 3, anyone());

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

        assertThat(chunks.search(axis(0), 2, anyone())).hasSize(2);
    }

    @Test
    void anEmptyKnowledgeBaseReturnsNothing() {
        assertThat(chunks.search(axis(0), 5, anyone())).isEmpty();
    }

    @Test
    void aPersonalDocumentAnswersOnlyItsOwner() {
        long mine = scoped("rieng.md", "000005", DocumentScope.PERSONAL, null);
        chunks.insertAll(mine, List.of(new NewChunk(0, "private", null, 1, 1, 1, axis(0))));

        assertThat(chunks.search(axis(0), 5, new DocumentVisibility("000005", Set.of()))).hasSize(1);
        assertThat(chunks.search(axis(0), 5, new DocumentVisibility("000004", Set.of(unit)))).isEmpty();
    }

    @Test
    void aUnitDocumentAnswersTheUnitAndItsChildrenNotASibling() {
        long id = scoped("don-vi.md", "000005", DocumentScope.UNIT, unit);
        chunks.insertAll(id, List.of(new NewChunk(0, "unit", null, 1, 1, 1, axis(0))));

        // A viewer's ids are their own unit plus the units above it.
        assertThat(chunks.search(axis(0), 5, new DocumentVisibility("000004", Set.of(unit)))).hasSize(1);
        assertThat(chunks.search(axis(0), 5, new DocumentVisibility("000004", Set.of(child, unit)))).hasSize(1);
        assertThat(chunks.search(axis(0), 5, new DocumentVisibility("000004", Set.of(sibling)))).isEmpty();
        assertThat(chunks.search(axis(0), 5, new DocumentVisibility("000004", Set.of()))).isEmpty();
    }

    @Test
    void theScopeFilterRunsBeforeTheTopK() {
        // Ten closer chunks the viewer may not read must not push out the one farther chunk they may.
        for (int i = 0; i < 10; i++) {
            long hidden = scoped("an-" + i + ".md", "000005", DocumentScope.PERSONAL, null);
            chunks.insertAll(hidden, List.of(new NewChunk(0, "hidden " + i, null, 1, 1, 1, axis(0))));
        }
        long shared = document("chung.md");
        chunks.insertAll(shared, List.of(new NewChunk(0, "shared", null, 1, 1, 1, blend(0, 1))));

        List<ChunkHit> hits = chunks.search(axis(0), 3, new DocumentVisibility("000004", Set.of()));

        assertThat(hits).extracting(ChunkHit::content).containsExactly("shared");
    }

    @Test
    void deletingChunksOfADocumentLeavesNothingToFind() {
        long id = document("xoa.md");
        chunks.insertAll(id, List.of(new NewChunk(0, "gone", null, 1, 1, 1, axis(0))));

        assertThat(chunks.deleteByDocument(id)).isEqualTo(1);
        assertThat(chunks.search(axis(0), 5, anyone())).isEmpty();
    }

    @Test
    void neighboursAreTheChunksBeforeAndAfterInTheSameDocumentAndOnlyOfVisibleDocuments() {
        long mine = scoped("rieng.md", "000005", DocumentScope.PERSONAL, null);
        chunks.insertAll(mine, List.of(
            new NewChunk(0, "zero", null, 1, 1, 1, axis(0)), new NewChunk(1, "one", null, 2, 2, 1, axis(1)),
            new NewChunk(2, "two", null, 3, 3, 1, axis(2)), new NewChunk(3, "three", null, 4, 4, 1, axis(3))));
        long middle = jdbc.queryForObject(
            "SELECT chunk_id FROM learning_hub.knowledge_document_chunks WHERE document_id = ? AND chunk_index = 1",
            Long.class, mine);
        long first = jdbc.queryForObject(
            "SELECT chunk_id FROM learning_hub.knowledge_document_chunks WHERE document_id = ? AND chunk_index = 0",
            Long.class, mine);

        var owner = chunks.neighbours(java.util.Map.of(middle, 0.9, first, 0.8), new DocumentVisibility("000005", Set.of()));
        var stranger = chunks.neighbours(java.util.Map.of(middle, 0.9), new DocumentVisibility("000004", Set.of()));

        assertThat(owner.get(middle)).extracting(ChunkHit::content).containsExactly("zero", "two");
        assertThat(owner.get(middle)).allSatisfy(hit -> assertThat(hit.similarity()).isEqualTo(0.9));
        assertThat(owner.get(first)).extracting(ChunkHit::content).containsExactly("one");
        assertThat(stranger).isEmpty();
    }

    @Test
    void startUpFailsWhenTheConfiguredModelsSizeDiffersFromTheColumn() {
        var configured = properties.ollama();
        var other = new KnowledgeProperties(properties.storageDir(), properties.maxFileSize(), properties.worker(),
            properties.chunk(), properties.retrieval(), new KnowledgeProperties.Ollama(configured.baseUrl(),
            configured.chatModel(), "other-embed-model", configured.connectTimeout(), configured.readTimeout(),
            configured.keepAlive(), 768));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new EmbeddingDimensionCheck(jdbc, other).afterPropertiesSet())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("other-embed-model")
            .hasMessageContaining("768").hasMessageContaining("vector(1024)");
        new EmbeddingDimensionCheck(jdbc, properties).afterPropertiesSet();
    }

    private long org(String code, Long parent) {
        jdbc.update("""
            INSERT INTO learning_hub.orgs(parent_id, org_type, code, name, status, sort_no)
            SELECT ?, 'UNIT', ?, ?, 'active', 0 WHERE NOT EXISTS (SELECT 1 FROM learning_hub.orgs WHERE code = ?)
            """, parent, code, code, code);
        return jdbc.queryForObject("SELECT org_id FROM learning_hub.orgs WHERE code = ?", Long.class, code);
    }

    private long scoped(String fileName, String owner, DocumentScope scope, Long orgId) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setUploadedBy(owner);
        document.setScope(scope);
        document.setScopeOrgId(orgId);
        document.setFileName(fileName);
        document.setMimeType("text/markdown");
        document.setSizeBytes(1);
        document.setStorageKey("00000000-0000-0000-0000-000000000001");
        return documents.saveAndFlush(document).getId();
    }

    private static DocumentVisibility anyone() {
        return new DocumentVisibility("000004", Set.of());
    }

    private long document(String fileName) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setUploadedBy("000005");
        document.setScope(DocumentScope.GLOBAL);
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
