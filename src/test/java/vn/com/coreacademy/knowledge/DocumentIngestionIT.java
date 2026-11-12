package vn.com.coreacademy.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.entity.DocumentStatus;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;
import vn.com.coreacademy.knowledge.repository.KnowledgeDocumentRepository;
import vn.com.coreacademy.knowledge.service.DocumentChecksumBackfill;
import vn.com.coreacademy.knowledge.service.DocumentIngestionWorker;
import vn.com.coreacademy.knowledge.service.KnowledgeFileStore;
import vn.com.coreacademy.knowledge.service.KnowledgeReindexService;
import vn.com.coreacademy.knowledge.support.FakeEmbeddings;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/** The queue and worker against a real pgvector database; only Ollama is replaced. */
class DocumentIngestionIT extends KnowledgeIntegrationTest {
    @MockitoBean
    private OllamaClient ollama;

    @Autowired
    private DocumentIngestionWorker worker;

    @Autowired
    private KnowledgeReindexService reindex;

    @Autowired
    private KnowledgeDocumentRepository documents;

    @Autowired
    private KnowledgeFileStore store;

    @Autowired
    private DocumentChecksumBackfill backfill;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyKnowledgeBase() {
        jdbc.update("DELETE FROM learning_hub.knowledge_document_chunks");
        jdbc.update("DELETE FROM learning_hub.knowledge_documents");
        when(ollama.embed(anyList())).thenAnswer(FakeEmbeddings.everyInputOnAxisZero());
    }

    @Test
    void aQueuedMarkdownDocumentBecomesReadyWithItsChunks() throws IOException {
        long id = queue("quy-trinh.md", "text/markdown", "# Quy trình\n\nBước một.\nBước hai.\n");

        Optional<Long> claimed = worker.claimNext();
        assertThat(claimed).contains(id);
        assertThat(statusOf(id)).isEqualTo("processing");

        worker.process(id);

        KnowledgeDocument done = documents.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(done.getChunkCount()).isEqualTo(1);
        assertThat(done.getAttempts()).isEqualTo(1);
        assertThat(done.getProcessedAt()).isNotNull();
        assertThat(done.getErrorMessage()).isNull();
        List<String> rows = jdbc.queryForList("""
            SELECT line_start || '-' || line_end || ':' || coalesce(page_number::text, 'null')
            FROM learning_hub.knowledge_document_chunks WHERE document_id = ?
            """, String.class, id);
        assertThat(rows).containsExactly("1-4:null");
    }

    @Test
    void anExtractionFailureMarksTheDocumentFailedWithTheReason() throws IOException {
        long id = queue("trong.md", "text/markdown", "   \n\n  \n");
        worker.claimNext();

        worker.process(id);

        KnowledgeDocument failed = documents.findById(id).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("Tài liệu không có nội dung văn bản.");
        assertThat(failed.getProcessedAt()).isNotNull();
        assertThat(chunkCount(id)).isZero();
    }

    @Test
    void aPdfWithALegacyFontTextLayerFailsWithTheReasonAndNoChunks() throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream content =
                     new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(16);
                content.newLineAtOffset(50, 700);
                for (String row : List.of("Quy tr×nh ®ãng sæ cuèi ngµy", "Hä thèng qu¶n lý tµi kho¶n kh¸ch hµng",
                    "B­íc 1: kiÓm tra c¸c giao dÞch ch­a h¹ch to¸n")) {
                    content.showText(row);
                    content.newLine();
                }
                content.endText();
            }
            pdf.save(bytes);
        }
        long id = queue("tcvn3.pdf", "application/pdf", bytes.toByteArray());
        worker.claimNext();

        worker.process(id);

        KnowledgeDocument failed = documents.findById(id).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("Tệp có phông chữ hoặc bảng mã không đọc được");
        assertThat(chunkCount(id)).isZero();
    }

    @Test
    void anUnexpectedErrorFailsWithAGenericMessageAndKeepsNoChunks() throws IOException {
        long id = queue("loi.md", "text/markdown", "nội dung\n");
        when(ollama.embed(anyList())).thenThrow(new IllegalStateException("secret document text"));
        worker.claimNext();

        worker.process(id);

        KnowledgeDocument failed = documents.findById(id).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.getErrorMessage())
            .isEqualTo("Không xử lý được tài liệu. Vui lòng thử lại hoặc liên hệ quản trị.");
        assertThat(chunkCount(id)).isZero();
    }

    @Test
    void whenOllamaIsDownTheDocumentGoesBackToTheQueueAndFailsAfterThreeAttempts() throws IOException {
        long id = queue("cho.md", "text/markdown", "nội dung\n");
        when(ollama.embed(anyList())).thenThrow(new OllamaUnavailableException("down", null));

        for (int attempt = 1; attempt <= 2; attempt++) {
            assertThat(worker.claimNext()).contains(id);
            worker.process(id);
            assertThat(statusOf(id)).isEqualTo("queued");
            assertThat(documents.findById(id).orElseThrow().getAttempts()).isEqualTo(attempt);
        }

        assertThat(worker.claimNext()).contains(id);
        worker.process(id);

        KnowledgeDocument failed = documents.findById(id).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(3);
        assertThat(failed.getErrorMessage())
            .isEqualTo("Không kết nối được dịch vụ AI sau 3 lần thử. Vui lòng tải lại tệp sau.");
    }

    @Test
    void documentsLeftProcessingByAStoppedRunGoBackToTheQueue() throws IOException {
        long id = queue("dang-do.md", "text/markdown", "nội dung\n");
        worker.claimNext();
        assertThat(statusOf(id)).isEqualTo("processing");

        worker.requeueInterrupted();

        assertThat(statusOf(id)).isEqualTo("queued");
    }

    @Test
    void concurrentWorkersNeverClaimTheSameDocument() throws Exception {
        Set<Long> queued = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            queued.add(queue("tai-lieu-" + i + ".md", "text/markdown", "nội dung " + i + "\n"));
        }

        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<List<Long>>> claimers = new ArrayList<>();
            for (int worker = 0; worker < 6; worker++) {
                claimers.add(() -> {
                    List<Long> mine = new ArrayList<>();
                    Optional<Long> next;
                    while ((next = this.worker.claimNext()).isPresent()) {
                        mine.add(next.get());
                    }
                    return mine;
                });
            }
            List<Long> all = new ArrayList<>();
            for (Future<List<Long>> result : pool.invokeAll(claimers)) {
                all.addAll(result.get());
            }

            assertThat(all).hasSize(12).doesNotHaveDuplicates();
            assertThat(new HashSet<>(all)).isEqualTo(queued);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pollSkipsTheCycleWhileOllamaIsDownAndProcessesOnceItIsBack() throws IOException {
        long id = queue("poll.md", "text/markdown", "nội dung\n");
        when(ollama.isAvailable()).thenReturn(false);

        worker.poll();

        assertThat(statusOf(id)).isEqualTo("queued");

        when(ollama.isAvailable()).thenReturn(true);
        worker.poll();

        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(statusOf(id)).isEqualTo("ready"));
    }

    @Test
    void chunksCarryTheirSectionPathAndStampsAndTheEmbeddedTextLeadsWithTitleAndPath() throws IOException {
        long id = queue("quy-trinh.md", "text/markdown",
            "# Quy trình EOD\n\nBước một.\n\n## Xử lý lỗi\n\nDừng job.\n");
        worker.claimNext();

        worker.process(id);

        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
            SELECT section_path, content, length(content_hash) AS hash_length, chunker_version, embed_model
              FROM learning_hub.knowledge_document_chunks WHERE document_id = ? ORDER BY chunk_index
            """, id);
        assertThat(rows).extracting(row -> row.get("section_path")).containsExactly("Quy trình EOD",
            "Quy trình EOD › Xử lý lỗi");
        assertThat(rows).extracting(row -> row.get("content")).containsExactly("# Quy trình EOD\nBước một.",
            "## Xử lý lỗi\nDừng job.");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("hash_length")).isEqualTo(64);
            assertThat(row.get("chunker_version")).isEqualTo(2);
            assertThat(row.get("embed_model")).isEqualTo("bge-m3");
        });
        org.mockito.ArgumentCaptor<List<String>> embedded = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(ollama).embed(embedded.capture());
        assertThat(embedded.getValue()).containsExactly("quy-trinh.md › Quy trình EOD\n# Quy trình EOD\nBước một.",
            "quy-trinh.md › Quy trình EOD › Xử lý lỗi\n## Xử lý lỗi\nDừng job.");
    }

    @Test
    void reindexAfterAVersionBumpReplacesTheChunksAndTheDocumentNeverHasNone() throws Exception {
        long id = queue("quy-trinh.md", "text/markdown", "# Quy trình EOD\n\nBước một.\n");
        worker.claimNext();
        worker.process(id);
        // As if cut by chunker version 1 and embedded by another model.
        jdbc.update("""
            UPDATE learning_hub.knowledge_document_chunks
               SET chunker_version = 1, embed_model = 'old-model', section_path = '' WHERE document_id = ?
            """, id);
        java.util.concurrent.atomic.AtomicInteger visibleWhileEmbedding = new java.util.concurrent.atomic.AtomicInteger(-1);
        when(ollama.embed(anyList())).thenAnswer(invocation -> {
            visibleWhileEmbedding.set(chunkCount(id));
            return FakeEmbeddings.everyInputOnAxisZero().answer(invocation);
        });

        reindex.start();

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
            assertThat(reindex.status().state()).isEqualTo(KnowledgeReindexService.State.FINISHED));
        assertThat(visibleWhileEmbedding.get()).as("old chunks answer while the new ones are embedded").isEqualTo(1);
        assertThat(reindex.status().done()).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(id)).isEqualTo("ready");
        assertThat(jdbc.queryForList("""
            SELECT chunker_version || '|' || embed_model || '|' || section_path
              FROM learning_hub.knowledge_document_chunks WHERE document_id = ?
            """, String.class, id)).containsExactly("2|bge-m3|Quy trình EOD");
        // Nothing is stale any more: a second run has nothing to do.
        assertThat(reindex.start().total()).isZero();
    }

    @Test
    void aReplacementSwapsTheChunksKeepsTheIdAndTheOldVersionAnswersUntilThen() throws IOException {
        long id = queue("cu.md", "text/markdown", "# Cũ\n\nnội dung cũ\n");
        worker.claimNext();
        worker.process(id);
        String oldKey = storageKeyOf(id);
        String newKey = stage(id, "moi.md", "# Mới\n\nnội dung mới hoàn toàn\n");

        Optional<Long> claimed = worker.claimNextReplacement();

        // Claimed but not done: the document is still ready and still answers with the old text.
        assertThat(claimed).contains(id);
        assertThat(jdbc.queryForObject("SELECT replacement_status FROM learning_hub.knowledge_documents "
            + "WHERE document_id = ?", String.class, id)).isEqualTo("processing");
        assertThat(statusOf(id)).isEqualTo("ready");
        assertThat(chunkTexts(id)).containsExactly("# Cũ\nnội dung cũ");

        worker.processReplacement(id);

        KnowledgeDocument done = documents.findById(id).orElseThrow();
        assertThat(done.getId()).isEqualTo(id);
        assertThat(done.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(done.getFileName()).isEqualTo("moi.md");
        assertThat(done.getStorageKey()).isEqualTo(newKey);
        assertThat(done.getChecksumSha256()).isEqualTo(sha256("# Mới\n\nnội dung mới hoàn toàn\n"));
        assertThat(done.getReplacementStatus()).isNull();
        assertThat(done.getReplacementStorageKey()).isNull();
        assertThat(chunkTexts(id)).hasSize(1).allMatch(text -> text.contains("nội dung mới hoàn toàn"));
        assertThat(done.getChunkCount()).isEqualTo(1);
        assertThat(Files.exists(store.resolve(oldKey))).isFalse();
        assertThat(Files.exists(store.resolve(newKey))).isTrue();
    }

    @Test
    void aFailedReplacementLeavesTheOldVersionInPlaceAndShowsTheError() throws IOException {
        long id = queue("cu.md", "text/markdown", "# Cũ\n\nnội dung cũ\n");
        worker.claimNext();
        worker.process(id);
        String oldKey = storageKeyOf(id);
        String newKey = stage(id, "moi.md", "# Mới\n\nnội dung mới\n");
        when(ollama.embed(anyList())).thenThrow(new IllegalStateException("secret document text"));
        worker.claimNextReplacement();

        worker.processReplacement(id);

        KnowledgeDocument kept = documents.findById(id).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(kept.getFileName()).isEqualTo("cu.md");
        assertThat(kept.getStorageKey()).isEqualTo(oldKey);
        assertThat(kept.getReplacementStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(kept.getReplacementError()).isNotBlank().doesNotContain("secret");
        assertThat(chunkTexts(id)).containsExactly("# Cũ\nnội dung cũ");
        assertThat(Files.exists(store.resolve(oldKey))).isTrue();
        assertThat(Files.exists(store.resolve(newKey))).isFalse();
    }

    @Test
    void anUnreadableReplacementFileFailsWithTheReasonAndChangesNothing() throws IOException {
        long id = queue("cu.md", "text/markdown", "# Cũ\n\nnội dung cũ\n");
        worker.claimNext();
        worker.process(id);
        stage(id, "trong.md", "   \n\n");
        worker.claimNextReplacement();

        worker.processReplacement(id);

        KnowledgeDocument kept = documents.findById(id).orElseThrow();
        assertThat(kept.getReplacementStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(kept.getReplacementError()).isEqualTo("Tài liệu không có nội dung văn bản.");
        assertThat(chunkTexts(id)).containsExactly("# Cũ\nnội dung cũ");
    }

    @Test
    void aReplacementWaitsInTheQueueWhenTheModelIsUnreachable() throws IOException {
        long id = queue("cu.md", "text/markdown", "# Cũ\n\nnội dung cũ\n");
        worker.claimNext();
        worker.process(id);
        stage(id, "moi.md", "# Mới\n\nnội dung mới\n");
        when(ollama.embed(anyList())).thenThrow(new OllamaUnavailableException("down", null));
        worker.claimNextReplacement();

        worker.processReplacement(id);

        assertThat(documents.findById(id).orElseThrow().getReplacementStatus()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(chunkTexts(id)).containsExactly("# Cũ\nnội dung cũ");
    }

    @Test
    void theStartUpFillComputesTheChecksumOfOlderDocumentsAndSkipsAMissingFile() throws IOException {
        long id = queue("cu.md", "text/markdown", "nội dung cũ\n");
        jdbc.update("UPDATE learning_hub.knowledge_documents SET checksum_sha256 = NULL");
        long lost = queue("mat.md", "text/markdown", "x");
        jdbc.update("UPDATE learning_hub.knowledge_documents SET checksum_sha256 = NULL, storage_key = ? "
            + "WHERE document_id = ?", UUID.randomUUID().toString(), lost);

        int filled = backfill.run();

        assertThat(filled).isEqualTo(1);
        assertThat(documents.findById(id).orElseThrow().getChecksumSha256()).isEqualTo(sha256("nội dung cũ\n"));
        assertThat(documents.findById(lost).orElseThrow().getChecksumSha256()).isNull();
        assertThat(backfill.run()).isZero();
    }

    private String storageKeyOf(long id) {
        return jdbc.queryForObject("SELECT storage_key FROM learning_hub.knowledge_documents WHERE document_id = ?",
            String.class, id);
    }

    private List<String> chunkTexts(long id) {
        return jdbc.queryForList("SELECT content FROM learning_hub.knowledge_document_chunks WHERE document_id = ? "
            + "ORDER BY chunk_index", String.class, id);
    }

    /** What the replace endpoint leaves behind: the new file stored and queued on the document row. */
    private String stage(long id, String fileName, String content) throws IOException {
        String key = UUID.randomUUID().toString();
        Files.write(store.resolve(key), content.getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
            UPDATE learning_hub.knowledge_documents
               SET replacement_status = 'queued', replacement_storage_key = ?, replacement_file_name = ?,
                   replacement_mime_type = 'text/markdown', replacement_size_bytes = ?, replacement_checksum = ?,
                   replacement_error = NULL, attempts = 0
             WHERE document_id = ?
            """, key, fileName, content.length(), sha256(content), id);
        return key;
    }

    private static String sha256(String content) {
        return vn.com.coreacademy.knowledge.chunking.ChunkText.sha256(content);
    }

    private long queue(String fileName, String mimeType, String content) throws IOException {
        return queue(fileName, mimeType, content.getBytes(StandardCharsets.UTF_8));
    }

    private long queue(String fileName, String mimeType, byte[] content) throws IOException {
        String key = UUID.randomUUID().toString();
        Files.createDirectories(store.resolve(key).getParent());
        Files.write(store.resolve(key), content);

        KnowledgeDocument document = new KnowledgeDocument();
        document.setUploadedBy("000005");
        document.setFileName(fileName);
        document.setMimeType(mimeType);
        document.setSizeBytes(content.length);
        document.setStorageKey(key);
        return documents.saveAndFlush(document).getId();
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM learning_hub.knowledge_documents WHERE document_id = ?",
            String.class, id);
    }

    private int chunkCount(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.knowledge_document_chunks WHERE document_id = ?",
            Integer.class, id);
    }
}
