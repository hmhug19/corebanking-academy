package vn.com.coreacademy.assistant.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Course generation: how many jobs a user may start per day, how often the worker looks for a step, and how long one
 * model call of a step may take (shorter than the chat read timeout, so a stuck call gives chat its turn back).
 */
@Validated
@ConfigurationProperties("app.assistant.generation")
public record GenerationProperties(@Min(1) int jobsPerDay, @NotNull Duration pollInterval,
                                   @NotNull Duration sourceRetention, @NotNull Duration modelCallTimeout) {
    @ConstructorBinding
    public GenerationProperties {
    }

    /** Keeps the default call limit of 90 seconds. */
    public GenerationProperties(int jobsPerDay, Duration pollInterval, Duration sourceRetention) {
        this(jobsPerDay, pollInterval, sourceRetention, Duration.ofSeconds(90));
    }

    /** The two-argument form keeps the default retention of a course source: 30 days after its job ended. */
    public GenerationProperties(int jobsPerDay, Duration pollInterval) {
        this(jobsPerDay, pollInterval, Duration.ofDays(30));
    }
}
