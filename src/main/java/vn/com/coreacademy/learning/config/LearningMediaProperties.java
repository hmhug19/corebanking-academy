package vn.com.coreacademy.learning.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import vn.com.coreacademy.storage.media.MediaProbe;

/** Tham số vận hành media; mặc định giữ hành vi cũ, bind qua app.learning.media.* hoặc biến môi trường. */
@Component("learningMediaProperties")
@ConfigurationProperties("app.learning.media")
@Validated
@Getter
@Setter
@NoArgsConstructor
public class LearningMediaProperties {
    /** Auto transcode threads stop here: past 4 the memory grows faster than the time falls. */
    private static final int AUTO_THREADS_CAP = 4;

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
    private Duration transcodeTimeout = Duration.ofHours(6);

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

    /**
     * Decoder and encoder threads of one transcode; 0 uses the CPUs the JVM sees, at most 4. More threads are
     * faster (an AV1 1080p source: 2 threads 40 s, 4 threads 27 s per 2 minutes of video) but use more memory
     * (about 290 MB at 2, 330 MB at 4, 410 MB at 8). Env: APP_LEARNING_MEDIA_TRANSCODE_THREADS.
     */
    @Min(0)
    @Max(16)
    private int transcodeThreads = 0;

    /** x264 preset of a transcode; a faster one trades quality at the same bitrate for speed. */
    @NotBlank
    @Pattern(regexp = "ultrafast|superfast|veryfast|faster|fast|medium")
    private String transcodePreset = MediaProbe.DEFAULT_PRESET;

    @Value("${app.learning.media.gateway-key:${MEDIA_GATEWAY_KEY:}}")
    private String gatewayKey = "";

    /** The threads one transcode actually uses. */
    public int effectiveTranscodeThreads() {
        return transcodeThreads > 0 ? transcodeThreads
            : Math.max(1, Math.min(AUTO_THREADS_CAP, Runtime.getRuntime().availableProcessors()));
    }

    @AssertTrue(message =
        "Media durations must be positive; heartbeat < processing lease and event gap < session lease")
    public boolean isTimingValid() {
        Duration[] durations = {sessionLease, mediaTokenTtl, uploadTtl, processingLease, processingHeartbeat,
            retryDelay, reservationGrace, maxEventGap, probeTimeout, transcodeTimeout, initialDelay};
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
