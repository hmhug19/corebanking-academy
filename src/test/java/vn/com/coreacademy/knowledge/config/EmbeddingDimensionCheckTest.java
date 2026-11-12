package vn.com.coreacademy.knowledge.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingDimensionCheckTest {
    @Test
    void aModelWhoseSizeDiffersFromTheColumnFailsWithAClearMessage() {
        assertThatThrownBy(() -> EmbeddingDimensionCheck.require("learning_hub.knowledge_document_chunks",
            "nomic-embed-text", 768, 1024))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nomic-embed-text")
            .hasMessageContaining("768")
            .hasMessageContaining("vector(1024)")
            .hasMessageContaining("knowledge_document_chunks");
    }

    @Test
    void aMatchingSizeOrAColumnWithoutFixedSizePasses() {
        assertThatCode(() -> EmbeddingDimensionCheck.require("t", "bge-m3", 1024, 1024)).doesNotThrowAnyException();
        assertThatCode(() -> EmbeddingDimensionCheck.require("t", "bge-m3", 1024, -1)).doesNotThrowAnyException();
        assertThat(EmbeddingDimensionCheck.TABLES).hasSize(2);
    }
}
