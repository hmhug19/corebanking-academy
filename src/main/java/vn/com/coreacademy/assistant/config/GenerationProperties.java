package vn.com.coreacademy.assistant.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Course generation: how many jobs a user may start per day, how often the worker looks for a step, and how long one
 * model call of a step may take (shorter than the chat read timeout, so a stuck call gives chat its turn back). A chat
 * attachment for Soạn cùng trợ lý is uploaded before any session uses it: a user uploads at most
 * {@code sourceUploadsPerDay} in 24 hours, and one no session or job uses is deleted after {@code unusedSourceTtl}.
 */
@Validated
@ConfigurationProperties("app.assistant.generation")
public record GenerationProperties(@Min(1) int jobsPerDay, @NotNull Duration pollInterval,
                                   @NotNull Duration sourceRetention, @NotNull Duration modelCallTimeout,
                                   @DefaultValue("10") @Min(1) int sourceUploadsPerDay,
                                   @DefaultValue("24h") @NotNull Duration unusedSourceTtl) {
    public static final int DEFAULT_SOURCE_UPLOADS_PER_DAY = 10;
    public static final Duration DEFAULT_UNUSED_SOURCE_TTL = Duration.ofHours(24);

    @ConstructorBinding
    public GenerationProperties {
    }

    /** Keeps the default upload cap (10 a day) and unused source lifetime (24 hours). */
    public GenerationProperties(int jobsPerDay, Duration pollInterval, Duration sourceRetention,
                                Duration modelCallTimeout) {
        this(jobsPerDay, pollInterval, sourceRetention, modelCallTimeout, DEFAULT_SOURCE_UPLOADS_PER_DAY,
            DEFAULT_UNUSED_SOURCE_TTL);
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
