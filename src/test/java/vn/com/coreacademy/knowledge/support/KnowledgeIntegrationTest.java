package vn.com.coreacademy.knowledge.support;

import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for tests that need the real schema: one throwaway pgvector database per test JVM, initialised with the four
 * SQL scripts exactly as an operator runs them (identity, learning, media/progress, knowledge). The properties override whatever a
 * local .env says, so these tests never reach another database.
 *
 * <p>Without Docker these classes are skipped unless knowledge.api.local explicitly selects the prepared,
 * disposable pgvector database on localhost. Both paths use the real vector type, never a real[] substitute.
 */
@SpringBootTest
@EnabledIf("databaseAvailable")
public abstract class KnowledgeIntegrationTest {
    protected static boolean databaseAvailable() {
        return Boolean.getBoolean("knowledge.api.local") || DockerClientFactory.instance().isDockerAvailable();
    }

    /** Started on first use, so a test class that is skipped for lack of Docker never touches it. */
    private static final class Database {
        static final PostgreSQLContainer<?> POSTGRES = start();

        private static PostgreSQLContainer<?> start() {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
                .withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_identity.sql"),
                    "/docker-entrypoint-initdb.d/01-identity.sql")
                .withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_learning.sql"),
                    "/docker-entrypoint-initdb.d/02-learning.sql")
                .withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_media_progress.sql"),
                    "/docker-entrypoint-initdb.d/03-media-progress.sql")
                .withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_knowledge.sql"),
                    "/docker-entrypoint-initdb.d/04-knowledge.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-10-assistant-conversations.sql"),
                    "/docker-entrypoint-initdb.d/04a-assistant-conversations.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-10-assistant-pending-actions.sql"),
                    "/docker-entrypoint-initdb.d/04b-assistant-pending-actions.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-10-assistant-feedback.sql"),
                    "/docker-entrypoint-initdb.d/04c-assistant-feedback.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-11-knowledge-document-scope.sql"),
                    "/docker-entrypoint-initdb.d/04x-knowledge-document-scope.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-11-course-content-index.sql"),
                    "/docker-entrypoint-initdb.d/04y-course-content-index.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-11-assistant-conversation-lesson-context.sql"),
                    "/docker-entrypoint-initdb.d/04za-assistant-conversation-lesson-context.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-12-course-generation-jobs.sql"),
                    "/docker-entrypoint-initdb.d/04zb-course-generation-jobs.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-13-course-revision-ai-generated.sql"),
                    "/docker-entrypoint-initdb.d/04zc-course-revision-ai-generated.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-14-course-revision-review-confirmed.sql"),
                    "/docker-entrypoint-initdb.d/04zd-course-revision-review-confirmed.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-15-course-job-soft-delete.sql"),
                    "/docker-entrypoint-initdb.d/04ze-course-job-soft-delete.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-16-assistant-retrieval-trace.sql"),
                    "/docker-entrypoint-initdb.d/04zf-assistant-retrieval-trace.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-16-knowledge-document-course-source-scope.sql"),
                    "/docker-entrypoint-initdb.d/04zg-knowledge-document-course-source-scope.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-17-course-job-owns-course.sql"),
                    "/docker-entrypoint-initdb.d/04zh-course-job-owns-course.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-18-course-index-coverage.sql"),
                    "/docker-entrypoint-initdb.d/04zi-course-index-coverage.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-19-chunker-v2-columns.sql"),
                    "/docker-entrypoint-initdb.d/04zj-chunker-v2-columns.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-20-document-title-checksum-replace.sql"),
                    "/docker-entrypoint-initdb.d/04zk-document-title-checksum-replace.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-21-assistant-feedback-review.sql"),
                    "/docker-entrypoint-initdb.d/04zl-assistant-feedback-review.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-22-system-audit-route.sql"),
                    "/docker-entrypoint-initdb.d/04zm-system-audit-route.sql")
                .withCopyFileToContainer(
                    MountableFile.forHostPath("docs/sql/upgrade/2026-11-23-revoked-sessions.sql"),
                    "/docker-entrypoint-initdb.d/04zn-revoked-sessions.sql")
                .withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_community.sql"),
                    "/docker-entrypoint-initdb.d/05-community.sql")
                .withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_assessment.sql"),
                    "/docker-entrypoint-initdb.d/05-assessment.sql")
                .withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_reporting.sql"),
                    "/docker-entrypoint-initdb.d/06-reporting.sql");
            postgres.start();
            return postgres;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (Boolean.getBoolean("knowledge.api.local")) {
            // Opt-in chỉ trỏ DB tổng hợp cố định trên localhost; không đọc DB_URL hoặc ghi schema tự động.
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + Integer.getInteger("knowledge.api.local.port", 25449)
                + "/knowledge_integration_check_20261028?currentSchema=learning_hub");
            registry.add("spring.datasource.username", () -> "codex_verify");
            registry.add("spring.datasource.password", () -> "");
        } else {
            PostgreSQLContainer<?> postgres = Database.POSTGRES;
            registry.add("spring.datasource.url",
                () -> postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                    + "currentSchema=learning_hub");
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
        registry.add("spring.config.import", () -> "optional:file:./tmp/community-empty.properties");
        registry.add("app.bootstrap.admin-id", () -> "");
        registry.add("app.bootstrap.admin-user", () -> "");
        registry.add("app.bootstrap.admin-password", () -> "");
        registry.add("app.jwt.secret", () -> "test-only-secret-with-at-least-32-bytes!!");
        // Storage is required by the application; these tests never use a real S3 endpoint or credentials.
        registry.add("app.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("app.storage.s3.access-key", () -> "test-only-access");
        registry.add("app.storage.s3.secret-key", () -> "test-only-secret");
        // Tests call the worker themselves; the scheduler must never fire on its own.
        registry.add("app.knowledge.worker.poll-interval", () -> "1h");
        registry.add("app.learning.course-index.poll-interval", () -> "1h");
        registry.add("app.assistant.generation.poll-interval", () -> "1h");
        registry.add("app.knowledge.storage-dir", () -> System.getProperty("java.io.tmpdir") + "/knowledge-test");
    }
}
