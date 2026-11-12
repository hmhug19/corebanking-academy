package vn.com.coreacademy.knowledge.config;

import java.time.Duration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * What the extractors refuse to read: too many pages, too much text, too slow, or text that is not readable
 * Vietnamese or English (a legacy font or code page). Every value is configurable under {@code app.knowledge.extraction}.
 */
@Validated
@ConfigurationProperties("app.knowledge.extraction")
public record ExtractionLimits(
    @Min(1) int maxPages,
    @Min(1) long maxCharacters,
    @NotNull Duration maxDuration,
    @DecimalMin("0.0") @DecimalMax("1.0") double minReadableWordShare) {

    public static final ExtractionLimits DEFAULTS = new ExtractionLimits(300, 1_500_000, Duration.ofSeconds(60), 0.5);
}
