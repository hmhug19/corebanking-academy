package vn.com.coreacademy.knowledge.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for tests that need the real schema: one throwaway pgvector database per test JVM, initialised with the four
 * SQL scripts exactly as an operator runs them (identity, learning, media/progress, knowledge). The properties override whatever a
 * local .env says, so these tests never reach another database.
 *
 * <p>Without a Docker daemon (for example inside {@code docker build}) these classes are skipped rather than failed;
 * run {@code mvn clean verify} with Docker before merging so they do run.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
public abstract class KnowledgeIntegrationTest {
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
                    "/docker-entrypoint-initdb.d/04-knowledge.sql");
            postgres.start();
            return postgres;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgreSQLContainer<?> postgres = Database.POSTGRES;
        registry.add("spring.datasource.url",
            () -> postgres.getJdbcUrl() + (postgres.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=learning_hub");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-secret-with-at-least-32-bytes!!");
        // Storage is required by the application; these tests never use a real S3 endpoint or credentials.
        registry.add("app.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("app.storage.s3.access-key", () -> "test-only-access");
        registry.add("app.storage.s3.secret-key", () -> "test-only-secret");
        // Tests call the worker themselves; the scheduler must never fire on its own.
        registry.add("app.knowledge.worker.poll-interval", () -> "1h");
        registry.add("app.knowledge.storage-dir", () -> System.getProperty("java.io.tmpdir") + "/knowledge-test");
    }
}
