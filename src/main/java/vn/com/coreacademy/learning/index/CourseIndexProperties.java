package vn.com.coreacademy.learning.index;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Course index worker settings under {@code app.learning.course-index}. A revision that fails to index is retried
 * after {@code retryDelay}, doubling each time, until {@code maxAttempts} attempts have failed.
 */
@Validated
@ConfigurationProperties("app.learning.course-index")
public record CourseIndexProperties(@NotNull Duration pollInterval, @Min(1) int maxAttempts,
                                    @NotNull Duration retryDelay) {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CourseIndexProperties.class)
    static class Registration {
    }
}
