package vn.com.coreacademy.community.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component("communityJobsProperties")
@ConfigurationProperties("app.community.jobs")
@Validated
@Getter
@Setter
public class CommunityJobsProperties {
    private boolean enabled;
    private String workerUserId;
    @Min(100)
    private long pollMs = 3_000;
    @NotNull
    private Duration initialDelay = Duration.ofSeconds(15);
    @NotNull
    private Duration lease = Duration.ofSeconds(300);
    @NotNull
    private Duration heartbeat = Duration.ofSeconds(60);
    @NotNull
    private Duration retryDelay = Duration.ofSeconds(30);
    @NotNull
    private Duration secondRetryDelay = Duration.ofSeconds(120);

    @AssertTrue(message = "Enabled jobs require explicit actor and positive durations; heartbeat < lease")
    public boolean isValidConfiguration() {
        if (enabled && (workerUserId == null || workerUserId.isBlank())) {
            return false;
        }
        for (Duration value : new Duration[] {initialDelay, lease, heartbeat, retryDelay, secondRetryDelay}) {
            if (value == null || value.isNegative() || value.isZero()) {
                return false;
            }
        }
        return heartbeat.compareTo(lease) < 0;
    }
}
