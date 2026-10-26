package vn.com.coreacademy.storage.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.storage.s3")
public record S3Properties(@NotBlank String endpoint, @NotBlank String bucket, @NotBlank String region,
                           @NotBlank String accessKey, @NotBlank String secretKey, boolean pathStyleAccess,
                           @Min(1) @Max(60) int connectTimeoutSeconds,
                           @Min(1) @Max(600) int readTimeoutSeconds,
                           @Min(1) @Max(3600) int callTimeoutSeconds) {
    private static final int MAX_MULTIPART_CALL_SECONDS = 60;

    public int multipartCallTimeoutSeconds() {
        return Math.min(callTimeoutSeconds, MAX_MULTIPART_CALL_SECONDS);
    }

    @Override
    public String toString() {
        return "S3Properties[credentials=<redacted>]";
    }
}
