package vn.com.coreacademy.assistant.eval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.chunking.Chunk;
import vn.com.coreacademy.knowledge.chunking.Chunker;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.entity.DocumentScope;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;
import vn.com.coreacademy.knowledge.extraction.DocxTextExtractor;
import vn.com.coreacademy.knowledge.extraction.ExtractedText;
import vn.com.coreacademy.knowledge.extraction.MarkdownTextExtractor;
import vn.com.coreacademy.knowledge.extraction.PdfTextExtractor;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.NewChunk;
import vn.com.coreacademy.knowledge.repository.DocumentVisibility;
import vn.com.coreacademy.knowledge.repository.KnowledgeDocumentRepository;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval, measured: the fixture documents go through the real pipeline (extract, chunk, embed with the real
 * {@code bge-m3}, store in a pgvector database), then every case of {@link RetrievalSet} is searched as its user and
 * scored. Not in the default run ({@code mvn test -Dtest=RetrievalEval}); needs Docker and Ollama with {@code bge-m3}.
 * The report goes to {@code target/retrieval-eval.txt}. The numbers are advisory until the set is approved, except that a
 * document returned to a user who may not read it always fails the eval.
 */
class RetrievalEval extends KnowledgeIntegrationTest {
    private static final int DEEP = 20;
    private static final int TOP_K_REPORTED = 5;

    @Autowired
    private OllamaClient ollama;
    @Autowired
    private Chunker chunker;
    @Autowired
    private KnowledgeProperties properties;
    @Autowired
    private DocumentChunkRepository chunks;
    @Autowired
    private KnowledgeDocumentRepository documents;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PdfTextExtractor pdf;
    @Autowired
    private MarkdownTextExtractor markdown;
    @Autowired
    private DocxTextExtractor docx;

    @Test
    void searchesEveryCaseThroughTheRealPipelineAndReportsTheNumbersPerGroup(@TempDir Path directory)
        throws IOException {
        RetrievalSet.Fixtures fixtures = RetrievalSet.fixtures();
        RetrievalSet.Cases set = RetrievalSet.cases();
        jdbc.update("DELETE FROM learning_hub.knowledge_document_chunks");
        jdbc.update("DELETE FROM learning_hub.knowledge_documents");

        Map<String, Long> units = new HashMap<>();
        fixtures.users().forEach(user -> units.computeIfAbsent(user.unit(), this::org));
        Map<Long, String> docOfId = new HashMap<>();
        int chunkCount = 0;
        for (RetrievalSet.Doc doc : fixtures.documents()) {
            Path file = RetrievalFixtures.write(doc, directory);
            long id = insertDocument(doc, units);
            docOfId.put(id, doc.id());
            chunkCount += index(id, extract(doc, file));
        }

        List<RetrievalScore.Outcome> outcomes = new ArrayList<>();
        for (RetrievalSet.Case testCase : set.cases()) {
            RetrievalSet.User user = fixtures.users().stream().filter(u -> u.id().equals(testCase.user()))
                .findFirst().orElseThrow();
            DocumentVisibility visibility = new DocumentVisibility(user.id(), Set.of(units.get(user.unit())));
            // Today's search embeds the question alone; a follow-up is not yet searched together with its previous one.
            float[] vector = ollama.embed(List.of(testCase.question())).get(0);
            List<ChunkHit> hits = chunks.search(vector, DEEP, visibility);
            List<RetrievalScore.Found> ranked = hits.stream()
                .map(hit -> new RetrievalScore.Found(docOfId.get(hit.documentId()), hit.content())).toList();
            boolean empty = hits.stream().noneMatch(hit -> hit.similarity() >= properties.retrieval().minSimilarity());
            outcomes.add(new RetrievalScore.Outcome(testCase,
                testCase.expect() == null ? 0 : RetrievalScore.rank(ranked, testCase.expect()), empty,
                RetrievalScore.leaked(ranked, testCase.forbidden())));
        }

        List<RetrievalScore.Group> groups = RetrievalSet.GROUPS.stream()
            .map(name -> RetrievalScore.group(name, outcomes)).toList();
        StringBuilder report = new StringBuilder("RETRIEVAL EVAL set v").append(set.version()).append(", chunker ")
            .append(properties.chunk().targetTokens()).append('/').append(properties.chunk().maxTokens()).append(" tokens, ")
            .append(chunkCount).append(" chunks, top-k ").append(properties.retrieval().topK()).append(", min-similarity ")
            .append(properties.retrieval().minSimilarity()).append("\nNOT APPROVED: results are ADVISORY (recall@")
            .append(TOP_K_REPORTED).append(" over the best ").append(TOP_K_REPORTED).append(" of ").append(DEEP)
            .append(" results)\n");
        groups.forEach(group -> report.append(RetrievalScore.line(group)).append('\n'));
        outcomes.stream().filter(o -> o.input().expect() != null && o.rank() == 0)
            .forEach(o -> report.append("MISS ").append(o.input().id()).append(' ').append(o.input().question())
                .append('\n'));
        outcomes.stream().filter(RetrievalScore.Outcome::leaked)
            .forEach(o -> report.append("LEAK ").append(o.input().id()).append(" returned ")
                .append(o.input().forbidden()).append(" to ").append(o.input().user()).append('\n'));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/retrieval-eval.txt"), report);
        System.out.println(report);

        assertThat(outcomes).as("a document was returned to a user who may not read it").noneMatch(
            RetrievalScore.Outcome::leaked);
    }

    private ExtractedText extract(RetrievalSet.Doc doc, Path file) {
        return switch (doc.format()) {
            case "md" -> markdown.extract(file);
            case "docx" -> docx.extract(file);
            default -> pdf.extract(file);
        };
    }

    private int index(long documentId, ExtractedText text) {
        List<Chunk> pieces = chunker.chunk(text.lines());
        List<float[]> vectors = ollama.embed(pieces.stream().map(Chunk::content).toList());
        List<NewChunk> stored = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            Chunk piece = pieces.get(i);
            stored.add(new NewChunk(i, piece.content(), piece.page(), piece.lineStart(), piece.lineEnd(),
                piece.tokenEstimate(), vectors.get(i)));
        }
        chunks.insertAll(documentId, stored);
        return stored.size();
    }

    private long insertDocument(RetrievalSet.Doc doc, Map<String, Long> units) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setUploadedBy(doc.owner());
        document.setScope(DocumentScope.valueOf(doc.scope()));
        document.setScopeOrgId(doc.unit() == null ? null : units.get(doc.unit()));
        document.setFileName(doc.file());
        document.setMimeType(switch (doc.format()) {
            case "md" -> "text/markdown";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "application/pdf";
        });
        document.setSizeBytes(1);
        document.setStorageKey("00000000-0000-0000-0000-000000000001");
        return documents.saveAndFlush(document).getId();
    }

    private long org(String code) {
        jdbc.update("""
            INSERT INTO learning_hub.orgs(parent_id, org_type, code, name, status, sort_no)
            SELECT NULL, 'UNIT', ?, ?, 'active', 0 WHERE NOT EXISTS (SELECT 1 FROM learning_hub.orgs WHERE code = ?)
            """, code, code, code);
        return jdbc.queryForObject("SELECT org_id FROM learning_hub.orgs WHERE code = ?", Long.class, code);
    }
}
