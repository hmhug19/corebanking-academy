package vn.com.coreacademy.assessment.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Nền cho integration test Quiz Engine trên PostgreSQL thật, schema dựng từ đúng các file SQL vận hành.
 *
 * <p>Hai chế độ:
 * <ul>
 *   <li>Mặc định (CI): Testcontainers image pgvector, giống KnowledgeIntegrationTest.</li>
 *   <li>{@code -Dit.postgres.url=jdbc:postgresql://host:port/postgres} (máy không có Docker): tạo lại database
 *   {@code coreacademy_assessment_it} trên server đó và chạy các file SQL. Nếu server không có pgvector, cột vector
 *   của knowledge được tạo dạng real[] (entity không ánh xạ cột này) để ddl-auto=validate vẫn kiểm toàn bộ entity.</li>
 * </ul>
 * Không có cả hai thì test bị bỏ qua (không fail). Properties ghi đè .env nên test không chạm database khác.
 */
@SpringBootTest
@AutoConfigureMockMvc
// The Trợ lý ngoài is on by default in application.yml; ITs run with it off unless they extend ExternalAssistantIT.
@TestPropertySource(properties = "app.assistant.external.enabled=false")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(AssessmentIntegrationTest.DatabaseAvailable.class)
public abstract class AssessmentIntegrationTest {
    private static final String EXTERNAL_URL = System.getProperty("it.postgres.url");
    private static final String EXTERNAL_USER = System.getProperty("it.postgres.user", "postgres");
    private static final String EXTERNAL_PASSWORD = System.getProperty("it.postgres.password", "");
    private static final String DATABASE = "coreacademy_assessment_it";
    private static final List<String> SCRIPTS = List.of("learning_hub_identity.sql", "learning_hub_learning.sql",
        "learning_hub_media_progress.sql", "learning_hub_knowledge.sql", "learning_hub_assessment.sql",
        "upgrade/2026-11-07-flashcards.sql", "upgrade/2026-11-07-personal-flashcards.sql",
        "upgrade/2026-11-10-assistant-conversations.sql", "upgrade/2026-11-10-assistant-pending-actions.sql",
        "upgrade/2026-11-10-assistant-feedback.sql", "upgrade/2026-11-11-knowledge-document-scope.sql", "upgrade/2026-11-11-course-content-index.sql", "upgrade/2026-11-11-assistant-conversation-lesson-context.sql", "upgrade/2026-11-12-course-generation-jobs.sql", "upgrade/2026-11-13-course-revision-ai-generated.sql", "upgrade/2026-11-14-course-revision-review-confirmed.sql", "upgrade/2026-11-15-course-job-soft-delete.sql",
        "upgrade/2026-11-12-scorm12.sql",
        "upgrade/2026-11-13-course-scorm.sql",
        "upgrade/2026-11-13-scorm-course-lessons.sql",
        "upgrade/2026-11-13-course-revision-learning.sql",
        "upgrade/2026-11-16-assistant-retrieval-trace.sql", "upgrade/2026-11-16-knowledge-document-course-source-scope.sql",
        "upgrade/2026-11-17-course-job-owns-course.sql", "upgrade/2026-11-18-course-index-coverage.sql",
        "upgrade/2026-11-19-chunker-v2-columns.sql", "upgrade/2026-11-20-document-title-checksum-replace.sql",
        "upgrade/2026-11-21-assistant-feedback-review.sql", "upgrade/2026-11-22-system-audit-route.sql",
        "upgrade/2026-11-23-revoked-sessions.sql", "upgrade/2026-11-24-checkpoint-answers.sql",
        "upgrade/2026-11-24-course-drafting-sessions.sql", "upgrade/2026-11-25-course-drafting-reproposals.sql",
        "upgrade/2026-11-26-assistant-turn-drafting-step.sql", "upgrade/2026-11-27-external-assistant-connections.sql",
        "upgrade/2026-11-28-course-drafting-proposer.sql", "upgrade/2026-11-29-course-drafting-confirmed.sql",
        "learning_hub_community.sql", "learning_hub_reporting.sql");

    /** Bật test khi có database ngoài được chỉ định hoặc có Docker cho Testcontainers. */
    static final class DatabaseAvailable implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (EXTERNAL_URL != null) {
                return ConditionEvaluationResult.enabled("External PostgreSQL " + EXTERNAL_URL);
            }
            return DockerClientFactory.instance().isDockerAvailable()
                ? ConditionEvaluationResult.enabled("Docker available")
                : ConditionEvaluationResult.disabled("No Docker and no -Dit.postgres.url");
        }
    }

    private record Target(String url, String user, String password) {
    }

    /** Khởi tạo một lần cho mỗi JVM test. */
    private static final class Database {
        static final Target TARGET = EXTERNAL_URL != null ? external() : container();

        private static Target container() {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
            for (int i = 0; i < SCRIPTS.size(); i++) {
                postgres.withCopyFileToContainer(MountableFile.forHostPath("docs/sql/" + SCRIPTS.get(i)),
                    "/docker-entrypoint-initdb.d/" + String.format("%02d", i + 1) + "-" + Path.of(SCRIPTS.get(i)).getFileName());
            }
            postgres.start();
            return new Target(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        private static Target external() {
            String adminUrl = EXTERNAL_URL;
            String databaseUrl = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + DATABASE;
            try (Connection admin = DriverManager.getConnection(adminUrl, EXTERNAL_USER, EXTERNAL_PASSWORD);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
                statement.execute("CREATE DATABASE " + DATABASE + " ENCODING 'UTF8'");
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot recreate test database", exception);
            }
            try (Connection connection = DriverManager.getConnection(databaseUrl, EXTERNAL_USER, EXTERNAL_PASSWORD);
                 Statement statement = connection.createStatement()) {
                boolean pgvector = hasExtension(statement, "vector");
                for (String script : SCRIPTS) {
                    String sql = Files.readString(Path.of("docs/sql", script), StandardCharsets.UTF_8);
                    if (!pgvector && sql.contains("vector(1024)")) {
                        sql = withoutPgvector(sql);
                    }
                    // Mỗi file có BEGIN/COMMIT riêng; PgJDBC gửi cả file trong một lệnh như khi vận hành chạy psql -f.
                    statement.execute(sql);
                }
            } catch (SQLException | IOException exception) {
                throw new IllegalStateException("Cannot initialise test schema", exception);
            }
            return new Target(databaseUrl, EXTERNAL_USER, EXTERNAL_PASSWORD);
        }

        private static boolean hasExtension(Statement statement, String name) throws SQLException {
            try (ResultSet rows = statement.executeQuery(
                "SELECT count(*) FROM pg_available_extensions WHERE name = '" + name + "'")) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }

        /** Chỉ dùng cho server không cài pgvector: bỏ extension/HNSW index, cột embedding thành real[]. */
        private static String withoutPgvector(String sql) {
            return sql.lines()
            .filter(line -> !line.startsWith("CREATE EXTENSION") || !line.contains(" vector"))
                .map(line -> line.replace("vector(1024)", "real[]"))
                .collect(Collectors.joining("\n"))
                .replaceAll("(?is)CREATE INDEX [^;]*\\bUSING hnsw\\s*\\([^;]*;", "");
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> {
            String url = Database.TARGET.url();
            return url + (url.contains("?") ? "&" : "?") + "currentSchema=learning_hub";
        });
        registry.add("spring.datasource.username", () -> Database.TARGET.user());
        registry.add("spring.datasource.password", () -> Database.TARGET.password());
        registry.add("app.jwt.secret", () -> "test-only-secret-with-at-least-32-bytes!!");
        registry.add("app.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("app.storage.s3.access-key", () -> "test-only-access");
        registry.add("app.storage.s3.secret-key", () -> "test-only-secret");
        registry.add("app.knowledge.worker.poll-interval", () -> "1h");
        registry.add("app.learning.course-index.poll-interval", () -> "1h");
        registry.add("app.assistant.generation.poll-interval", () -> "1h");
        registry.add("app.knowledge.storage-dir", () -> System.getProperty("java.io.tmpdir") + "/knowledge-test");
        // Test gọi job tự nộp trực tiếp; scheduler không được tự chạy xen vào dữ liệu đang dựng.
        registry.add("app.assessment.auto-submit-interval", () -> "1h");
        registry.add("app.assessment.auto-submit-initial-delay", () -> "1h");
    }
}
