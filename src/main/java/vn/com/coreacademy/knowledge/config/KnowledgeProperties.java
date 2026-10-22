package vn.com.coreacademy.knowledge.config;

import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** Cấu hình kho tri thức; mọi giá trị đổi theo môi trường nằm ở application.yml/biến môi trường, không trong Java. */
@Validated
@ConfigurationProperties("app.knowledge")
public record KnowledgeProperties(
    @NotNull Path storageDir,
    @NotNull DataSize maxFileSize,
    @Valid @NotNull Worker worker,
    @Valid @NotNull Chunk chunk,
    @Valid @NotNull Retrieval retrieval,
    @Valid @NotNull Ollama ollama) {

    public record Worker(@NotNull Duration pollInterval, @Min(1) int concurrency, @Min(1) int maxAttempts) {
    }

    /** Giới hạn tính theo token ước lượng (số ký tự / 3), không dùng tokenizer thật của bge-m3. */
    public record Chunk(@Min(1) int targetTokens, @Min(1) int maxTokens, @Min(0) int overlapTokens) {
    }

    public record Retrieval(@Min(1) int topK, @DecimalMin("0.0") @DecimalMax("1.0") double minSimilarity) {
    }

    public record Ollama(
        @NotBlank String baseUrl,
        @NotBlank String chatModel,
        @NotBlank String embedModel,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotNull Duration keepAlive) {
    }
}
