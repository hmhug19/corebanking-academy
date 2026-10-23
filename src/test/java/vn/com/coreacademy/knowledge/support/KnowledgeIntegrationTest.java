package vn.com.coreacademy.knowledge.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for tests that need the real schema: one throwaway pgvector database per test JVM, initialised with the three
 * SQL scripts exactly as an operator runs them (identity, learning, knowledge). The properties override whatever a
 * local .env says, so these tests never reach another database.
 */
@SpringBootTest
public abstract class KnowledgeIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
        .withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_identity.sql"),
            "/docker-entrypoint-initdb.d/01-identity.sql")
        .withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_learning.sql"),
            "/docker-entrypoint-initdb.d/02-learning.sql")
        .withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_knowledge.sql"),
            "/docker-entrypoint-initdb.d/03-knowledge.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
            () -> POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=learning_hub");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> "test-only-secret-with-at-least-32-bytes!!");
        // Tests call the worker themselves; the scheduler must never fire on its own.
        registry.add("app.knowledge.worker.poll-interval", () -> "1h");
        registry.add("app.knowledge.storage-dir", () -> System.getProperty("java.io.tmpdir") + "/knowledge-test");
    }
}
