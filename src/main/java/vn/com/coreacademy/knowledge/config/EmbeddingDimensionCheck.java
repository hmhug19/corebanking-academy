package vn.com.coreacademy.knowledge.config;

import java.util.List;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stops the application at start-up when the configured embed model's vector size differs from the size of the chunk
 * columns, so a model swap cannot silently fail on every upload or leave the index half in the old size.
 */
@Component
public class EmbeddingDimensionCheck implements InitializingBean {
    static final List<String> TABLES = List.of("learning_hub.knowledge_document_chunks",
        "learning_hub.course_content_chunks");

    private final JdbcTemplate jdbc;
    private final KnowledgeProperties.Ollama ollama;

    public EmbeddingDimensionCheck(JdbcTemplate jdbc, KnowledgeProperties properties) {
        this.jdbc = jdbc;
        this.ollama = properties.ollama();
    }

    @Override
    public void afterPropertiesSet() {
        for (String table : TABLES) {
            List<Integer> found = jdbc.queryForList("""
                SELECT a.atttypmod FROM pg_attribute a
                 WHERE a.attrelid = to_regclass(?) AND a.attname = 'embedding' AND NOT a.attisdropped
                """, Integer.class, table);
            // A table that does not exist yet is the schema validation's business, not this check's.
            if (!found.isEmpty()) {
                require(table, ollama.embedModel(), ollama.embedDimensions(), found.get(0));
            }
        }
    }

    /** pgvector keeps the size of {@code vector(n)} as the type modifier; -1 means no fixed size. */
    static void require(String table, String model, int configured, int column) {
        if (column > 0 && column != configured) {
            throw new IllegalStateException("Embed model '" + model + "' is configured for " + configured
                + " dimensions (app.knowledge.ollama.embed-dimensions) but " + table + ".embedding is vector("
                + column + "). Use a model with " + column + " dimensions, or migrate the column and re-index.");
        }
    }
}
