package vn.com.coreacademy.assistant.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Giới hạn của agent; model, URL và timeout dùng chung cấu hình Ollama trong app.knowledge.ollama. */
@Validated
@ConfigurationProperties("app.assistant.agent")
public record AssistantProperties(
    @Min(1) int maxSteps,
    @Min(1) int historyMessages,
    @NotNull Duration actionTtl,
    @Min(1) int maxConcurrentTurns) {
}
