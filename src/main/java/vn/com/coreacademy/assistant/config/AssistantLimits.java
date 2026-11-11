package vn.com.coreacademy.assistant.config;

import jakarta.validation.constraints.Min;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Per-user limits shared by the agent and the document chat, so one user cannot saturate the single model host. */
@Validated
@ConfigurationProperties("app.assistant.limits")
public record AssistantLimits(@Min(1) int questionsPerHour) {
}
