package vn.com.coreacademy.community.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Worker chỉ chạy khi có actor kỹ thuật được cấu hình rõ; không tự tạo tài khoản hoặc dùng SYSTEM. */
@Component("communityProperties")
@ConfigurationProperties("app.community.media")
@Validated
@Getter
@Setter
public class CommunityProperties {
    private boolean workerEnabled;
    private boolean cleanupEnabled;
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
    @NotNull
    private Duration orphanRetention = Duration.ofHours(72);
    @NotNull
    private Duration uploadTtl = Duration.ofHours(24);
    @NotNull
    private Duration probeTimeout = Duration.ofSeconds(120);
    @NotNull
    private Duration reservationGrace = Duration.ofSeconds(30);
    @NotBlank
    private String ffprobe = "ffprobe";
    @NotBlank
    private String ffmpeg = "ffmpeg";

    @AssertTrue(message = "Worker requires explicit user ID; durations positive; heartbeat < lease")
    public boolean isValidConfiguration() {
        if ((workerEnabled || cleanupEnabled) && (workerUserId == null || workerUserId.isBlank())) {
            return false;
        }
        for (Duration value : new Duration[] {initialDelay, lease, heartbeat, retryDelay, secondRetryDelay,
            orphanRetention, uploadTtl, probeTimeout, reservationGrace}) {
            if (value == null || value.isNegative() || value.isZero()) {
                return false;
            }
        }
        return heartbeat.compareTo(lease) < 0;
    }
}
