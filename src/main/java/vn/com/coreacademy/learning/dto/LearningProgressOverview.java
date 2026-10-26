package vn.com.coreacademy.learning.dto;

import java.time.Instant;

public record LearningProgressOverview(Instant serverTime, String timeZone, Counts counts,
                                       Availability activity, Availability assessment, Availability competency) {
    public record Counts(Long total, Long completed, Long inProgress, Long notStarted, Long overdue) {
    }

    public record Availability(String availability) {
    }
}
