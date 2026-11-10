package vn.com.coreacademy.assistant.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Course generation: how many jobs a user may start per day and how often the worker looks for a step. */
@Validated
@ConfigurationProperties("app.assistant.generation")
public record GenerationProperties(@Min(1) int jobsPerDay, @NotNull Duration pollInterval) {
}
