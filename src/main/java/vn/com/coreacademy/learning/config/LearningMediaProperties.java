package vn.com.coreacademy.learning.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Tham số vận hành media; mặc định giữ hành vi cũ, bind qua app.learning.media.* hoặc biến môi trường. */
@Component("learningMediaProperties")
@ConfigurationProperties("app.learning.media")
@Validated
@Getter
@Setter
@NoArgsConstructor
public class LearningMediaProperties {
    @NotNull
    private Duration sessionLease = Duration.ofSeconds(60);

    @NotNull
    private Duration mediaTokenTtl = Duration.ofMinutes(5);

    @NotNull
    private Duration uploadTtl = Duration.ofHours(24);

    @NotNull
    private Duration processingLease = Duration.ofMinutes(5);

    @NotNull
    private Duration processingHeartbeat = Duration.ofMinutes(1);

    @NotNull
    private Duration retryDelay = Duration.ofSeconds(30);

    @NotNull
    private Duration reservationGrace = Duration.ofSeconds(30);

    @NotNull
    private Duration maxEventGap = Duration.ofSeconds(30);

    @NotNull
    private Duration probeTimeout = Duration.ofSeconds(120);

    @NotNull
    private Duration initialDelay = Duration.ofSeconds(15);

    @Min(1)
    private long pollMs = 3000;

    @Min(1)
    @Max(20)
    private int maxProcessingAttempts = 3;

    @NotBlank
    private String ffprobe = "ffprobe";

    @NotBlank
    private String ffmpeg = "ffmpeg";

    @Value("${app.learning.media.gateway-key:${MEDIA_GATEWAY_KEY:}}")
    private String gatewayKey = "";

    @AssertTrue(message =
        "Media durations must be positive; heartbeat < processing lease and event gap < session lease")
    public boolean isTimingValid() {
        Duration[] durations = {sessionLease, mediaTokenTtl, uploadTtl, processingLease, processingHeartbeat,
            retryDelay, reservationGrace, maxEventGap, probeTimeout, initialDelay};
        for (Duration duration : durations) {
            if (duration == null) {
                return true;
            }
            if (duration.compareTo(Duration.ofMillis(1)) < 0 || duration.compareTo(Duration.ofDays(365)) > 0) {
                return false;
            }
        }
        return processingHeartbeat.compareTo(processingLease) < 0 && maxEventGap.compareTo(sessionLease) < 0
            && mediaTokenTtl.getSeconds() >= 1;
    }
}
