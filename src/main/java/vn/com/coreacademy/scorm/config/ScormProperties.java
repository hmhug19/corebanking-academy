package vn.com.coreacademy.scorm.config;

import java.time.Duration;
import java.nio.file.Path;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Player origin must be separate from the LMS because imported packages execute JavaScript. */
@Getter
@Setter
@Validated
@ConfigurationProperties("app.scorm")
public class ScormProperties {
    @Min(1024) @Max(65535)
    private int playerPort = 8020;
    @NotBlank
    private String playerOrigin = "http://localhost:8020";
    @NotBlank
    private String lmsOrigin = "http://localhost:4200";
    @Min(1)
    private long maxZipBytes = 100L * 1024 * 1024;
    @Min(1)
    private long maxExpandedBytes = 500L * 1024 * 1024;
    @Min(1) @Max(10000)
    private int maxFiles = 2000;
    @NotNull
    private Duration launchTtl = Duration.ofMinutes(60);
    @NotNull
    private Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "academy-scorm");
}
