package vn.com.coreacademy.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.NewChunk;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class KnowledgeDocumentControllerTest extends KnowledgeIntegrationTest {
    private static final String DOCUMENTS = "/api/v1/knowledge/documents";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DocumentChunkRepository chunks;

    @Value("${app.knowledge.storage-dir}")
    private Path storageDir;

    @BeforeEach
    void emptyKnowledgeBase() throws IOException {
        jdbc.update("DELETE FROM learning_hub.knowledge_document_chunks");
        jdbc.update("DELETE FROM learning_hub.knowledge_documents");
        jdbc.update("DELETE FROM learning_hub.audit_log WHERE action = 'KNOWLEDGE_DOCUMENT_UPLOADED'");
        if (Files.isDirectory(storageDir)) {
            try (Stream<Path> files = Files.list(storageDir)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    @Test
    void uploadReturns201WithTheQueuedDocumentAndAuditsIt() throws Exception {
        mvc.perform(multipart(DOCUMENTS).file(markdown("quy-trinh.md", "# Quy trình\n")).with(as("000005")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.id").isNumber())
            .andExpect(jsonPath("$.data.fileName").value("quy-trinh.md"))
            .andExpect(jsonPath("$.data.mimeType").value("text/markdown"))
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.chunkCount").value(0))
            .andExpect(jsonPath("$.data.errorMessage").doesNotExist())
            .andExpect(jsonPath("$.data.uploadedBy.userId").value("000005"))
            .andExpect(jsonPath("$.data.uploadedBy.fullName").value("Giảng viên Demo"))
            .andExpect(jsonPath("$.data.storageKey").doesNotExist());

        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM learning_hub.audit_log
            WHERE action = 'KNOWLEDGE_DOCUMENT_UPLOADED' AND actor_id = '000005'
            """, Integer.class)).isEqualTo(1);
        assertThat(storedFiles()).isEqualTo(1);
    }

    @Test
    void uploadRefusesAnUnsupportedExtension() throws Exception {
        refused(new MockMultipartFile("file", "tinh.exe", "application/octet-stream", new byte[] {1}),
            "KNOWLEDGE-FILE_TYPE_UNSUPPORTED");
    }

    @Test
    void uploadRefusesAnEmptyFile() throws Exception {
        refused(new MockMultipartFile("file", "rong.md", "text/markdown", new byte[0]), "KNOWLEDGE-FILE_INVALID");
    }

    @Test
    void uploadRefusesBytesThatAreNotThePromisedType() throws Exception {
        refused(new MockMultipartFile("file", "gia.pdf", "application/pdf",
            "not a pdf".getBytes(StandardCharsets.US_ASCII)), "KNOWLEDGE-FILE_INVALID");
    }

    @Test
    void uploadRefusesADeclaredTypeThatContradictsTheExtension() throws Exception {
        refused(new MockMultipartFile("file", "a.pdf", "image/png", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)),
            "KNOWLEDGE-FILE_INVALID");
    }

    @Test
    void uploadRefusesAFileOverTheLimit() throws Exception {
        refused(new MockMultipartFile("file", "lon.md", "text/markdown", new byte[20 * 1024 * 1024 + 1]),
            "KNOWLEDGE-FILE_TOO_LARGE");
    }

    @Test
    void uploadWithoutATokenIs401() throws Exception {
        mvc.perform(multipart(DOCUMENTS).file(markdown("a.md", "x"))).andExpect(status().isUnauthorized());
        mvc.perform(get(DOCUMENTS)).andExpect(status().isUnauthorized());
    }

    @Test
    void aCallerWithoutTheAssistantFunctionCannotUploadListOrReadDocuments() throws Exception {
        // A temporary password empties the user's rights, AI_ASSISTANT included.
        jdbc.update("UPDATE learning_hub.users SET must_change_password = true WHERE user_id = ?", "000004");
        try {
            mvc.perform(multipart(DOCUMENTS).file(markdown("a.md", "x")).with(as("000004")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-ACCESS_DENIED"));
            mvc.perform(get(DOCUMENTS).with(as("000004")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-ACCESS_DENIED"));
            mvc.perform(get(DOCUMENTS + "/1").with(as("000004")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-ACCESS_DENIED"));
        } finally {
            jdbc.update("UPDATE learning_hub.users SET must_change_password = false WHERE user_id = ?", "000004");
        }
    }

    @Test
    void listShowsEveryonesDocumentsNewestFirstAndMineKeepsTheCallers() throws Exception {
        upload("000005", "cua-giang-vien.md");
        upload("000004", "cua-hoc-vien.md");

        mvc.perform(get(DOCUMENTS).with(as("000004")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(2))
            .andExpect(jsonPath("$.data.items[0].fileName").value("cua-hoc-vien.md"))
            .andExpect(jsonPath("$.data.items[0].uploadedBy.fullName").value("Học viên tiền gửi Demo"))
            .andExpect(jsonPath("$.data.items[1].fileName").value("cua-giang-vien.md"));

        mvc.perform(get(DOCUMENTS).param("mine", "true").with(as("000004")))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].fileName").value("cua-hoc-vien.md"));
    }

    @Test
    void listFiltersByRepeatedStatus() throws Exception {
        upload("000005", "cho.md");
        upload("000005", "dang-xu-ly.md");
        jdbc.update("""
            UPDATE learning_hub.knowledge_documents SET status = 'processing' WHERE file_name = 'dang-xu-ly.md'
            """);

        mvc.perform(get(DOCUMENTS).param("status", "queued").param("status", "processing").with(as("000005")))
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get(DOCUMENTS).param("status", "processing").with(as("000005")))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].status").value("processing"));
        mvc.perform(get(DOCUMENTS).param("status", "ready").with(as("000005")))
            .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void listRejectsAnUnknownStatusAndAnOversizedPage() throws Exception {
        mvc.perform(get(DOCUMENTS).param("status", "weird").with(as("000005")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.messageCode").value("CORE-VALIDATION_FAILED"));
        mvc.perform(get(DOCUMENTS).param("size", "101").with(as("000005")))
            .andExpect(jsonPath("$.messageCode").value("CORE-VALIDATION_FAILED"));
    }

    @Test
    void getReturnsOneDocumentOrNotFound() throws Exception {
        upload("000005", "mot.md");
        Long id = jdbc.queryForObject("SELECT document_id FROM learning_hub.knowledge_documents", Long.class);

        mvc.perform(get(DOCUMENTS + "/" + id).with(as("000004")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fileName").value("mot.md"));
        mvc.perform(get(DOCUMENTS + "/" + (id + 1000)).with(as("000004")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.messageCode").value("CORE-RESOURCE_NOT_FOUND"));
    }

    @Test
    void uploadDefaultsToTheUploadersUnit() throws Exception {
        mvc.perform(multipart(DOCUMENTS).file(markdown("don-vi.md", "x")).with(as("000005")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.scope").value("unit"))
            .andExpect(jsonPath("$.data.scopeOrgId").isNumber());
    }

    @Test
    void anInstructorCannotShareWithEveryoneButAnAdminCan() throws Exception {
        mvc.perform(multipart(DOCUMENTS).file(markdown("chung.md", "x")).param("scope", "global").with(as("000005")))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.knowledge_documents", Integer.class))
            .isZero();

        mvc.perform(multipart(DOCUMENTS).file(markdown("chung.md", "x")).param("scope", "global").with(as("000002")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.scope").value("global"))
            .andExpect(jsonPath("$.data.scopeOrgId").doesNotExist());
    }

    @Test
    void anUnknownScopeIsRefused() throws Exception {
        mvc.perform(multipart(DOCUMENTS).file(markdown("x.md", "x")).param("scope", "everyone").with(as("000005")))
            .andExpect(jsonPath("$.errors[0].field").value("scope"));
    }

    @Test
    void aPersonalDocumentIsNeitherListedNorReadableByAnotherUser() throws Exception {
        long id = uploadScoped("000005", "rieng.md", "personal");

        mvc.perform(get(DOCUMENTS).with(as("000004")))
            .andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get(DOCUMENTS + "/" + id).with(as("000004")))
            .andExpect(jsonPath("$.status").value("ERROR"));
        mvc.perform(get(DOCUMENTS).with(as("000005")))
            .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(delete(DOCUMENTS + "/" + id).with(as("000004")))
            .andExpect(jsonPath("$.status").value("ERROR"));
        assertThat(documentCount(id)).isEqualTo(1);
    }

    @Test
    void theOwnerDeletesADocumentWithItsChunksAndFile() throws Exception {
        long id = uploadScoped("000005", "xoa.md", "unit");
        chunks.insertAll(id, List.of(new NewChunk(0, "noi dung", null, 1, 1, 1, new float[1024])));

        mvc.perform(delete(DOCUMENTS + "/" + id).with(as("000005")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertThat(documentCount(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.knowledge_document_chunks", Integer.class))
            .isZero();
        assertThat(storedFiles()).isZero();
    }

    @Test
    void aLearnerCannotDeleteAnotherUsersUnitDocument() throws Exception {
        long id = uploadScoped("000005", "cua-giang-vien.md", "unit");

        mvc.perform(delete(DOCUMENTS + "/" + id).with(as("000004"))).andExpect(status().isForbidden());

        assertThat(documentCount(id)).isEqualTo(1);
    }

    @Test
    void anAdminInScopeDeletesAUnitDocument() throws Exception {
        long id = uploadScoped("000005", "cua-giang-vien.md", "unit");

        mvc.perform(delete(DOCUMENTS + "/" + id).with(as("000003")))
            .andExpect(jsonPath("$.status").value("SUCCESS"));

        assertThat(documentCount(id)).isZero();
    }

    @Test
    void aDocumentBeingProcessedCannotBeDeleted() throws Exception {
        long id = uploadScoped("000005", "dang-xu-ly.md", "unit");
        jdbc.update("UPDATE learning_hub.knowledge_documents SET status = 'processing' WHERE document_id = ?", id);

        mvc.perform(delete(DOCUMENTS + "/" + id).with(as("000005")))
            .andExpect(jsonPath("$.errors[0].code").value("PROCESSING"));

        assertThat(documentCount(id)).isEqualTo(1);
    }

    private long uploadScoped(String userId, String name, String scope) throws Exception {
        String body = mvc.perform(multipart(DOCUMENTS).file(markdown(name, "nội dung " + name)).param("scope", scope)
                .with(as(userId)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").path("id").asLong();
    }

    private int documentCount(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.knowledge_documents WHERE document_id = ?",
            Integer.class, id);
    }

    private void refused(MockMultipartFile file, String messageCode) throws Exception {
        mvc.perform(multipart(DOCUMENTS).file(file).with(as("000005")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.messageCode").value(messageCode));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.knowledge_documents", Integer.class))
            .isZero();
        assertThat(storedFiles()).isZero();
    }

    private void upload(String userId, String name) throws Exception {
        mvc.perform(multipart(DOCUMENTS).file(markdown(name, "nội dung " + name)).with(as(userId)))
            .andExpect(status().isCreated());
        // create_time is the database clock; keep uploads strictly ordered.
        Thread.sleep(5);
    }

    private long storedFiles() throws IOException {
        if (!Files.isDirectory(storageDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(storageDir)) {
            return files.count();
        }
    }

    private static MockMultipartFile markdown(String name, String content) {
        return new MockMultipartFile("file", name, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }

    private static RequestPostProcessor as(String userId) {
        return jwt().jwt(token -> token.subject(userId));
    }
}
