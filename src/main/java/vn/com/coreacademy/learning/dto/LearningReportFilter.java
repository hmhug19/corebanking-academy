package vn.com.coreacademy.learning.dto;

import java.time.Instant;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;

/** Báo cáo hiện trạng của lần học hiện hành; from/to lọc ngày ghi danh, cận phải không bao gồm. */
public record LearningReportFilter(@Size(max = 100) String q, @Size(max = 20) String userId,
                                   @Positive Long orgId, @Positive Long teamId, @Positive Long courseId,
                                   @Positive Long programId, @Positive Long fieldId, String status, Boolean overdue,
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
    public static LearningReportFilter empty() {
        return enrolledBetween(null, null);
    }

    public static LearningReportFilter enrolledBetween(Instant from, Instant to) {
        return new LearningReportFilter(null, null, null, null, null, null, null, null, null, from, to);
    }
}
