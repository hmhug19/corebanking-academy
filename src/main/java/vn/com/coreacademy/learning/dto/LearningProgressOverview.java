package vn.com.coreacademy.learning.dto;

import java.time.Instant;

public record LearningProgressOverview(Instant serverTime, String timeZone, Counts counts,
                                       Availability activity, Availability assessment, Availability competency) {
    /**
     * assigned và mandatory là tổng của danh sách khóa của tôi với assigned=true hoặc requirement=mandatory, nên tab
     * trên màn hình học tập luôn khớp với danh sách.
     */
    public record Counts(Long total, Long completed, Long inProgress, Long notStarted, Long overdue, Long assigned,
                         Long mandatory) {
    }

    public record Availability(String availability) {
    }
}
