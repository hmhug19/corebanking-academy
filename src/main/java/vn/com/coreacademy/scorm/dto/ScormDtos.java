package vn.com.coreacademy.scorm.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** JSON contracts; package file keys and capability hashes remain server-side. */
public final class ScormDtos {
    private ScormDtos() { }

    public record PackageView(Long id, Long ownerOrgId, String title, String originalName, long sizeBytes,
                              int fileCount, String scormVersion, boolean published, boolean canManage,
                              long rowVersion, Instant createTime, List<String> warnings, ProgressView progress) { }
    public record ProgressView(String lessonStatus, BigDecimal scoreRaw, BigDecimal scoreMin, BigDecimal scoreMax,
                               String lessonLocation, boolean resumable, Instant updateTime) { }
    public record PackagePage(List<PackageView> items, int page, int size, long totalElements, int totalPages) { }
    public record LaunchRequest(boolean preview, @Positive Long enrollmentId,
                                @Positive Long lessonId) {
        public LaunchRequest(boolean preview) { this(preview, null, null); }
    }
    public record VersionRequest(@NotNull @Min(0) Long expectedVersion) { }
    public record LaunchView(String launchUrl, Instant expiresAt, boolean preview, ProgressView progress) { }
    public record RuntimeRequest(@Min(1) long sequence,
                                 @NotNull @Size(max = 600) Map<String, String> values, boolean finished) { }
    public record RuntimeReceipt(long sequence, ProgressView progress) { }
}
