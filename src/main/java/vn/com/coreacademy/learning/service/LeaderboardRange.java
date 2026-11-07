package vn.com.coreacademy.learning.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;

import vn.com.coreacademy.learning.constants.LearningCodes.LeaderboardPeriod;

/**
 * Kỳ của bảng xếp hạng theo giờ Asia/Ho_Chi_Minh: tuần là thứ Hai-Chủ nhật, tháng là tháng dương lịch, lùi back kỳ so với
 * kỳ chứa today. Kỳ "tất cả" không có ngày đầu và ngày cuối. from và to là ngày đầu và ngày cuối (gồm cả hai); end là
 * ngày đầu của kỳ kế tiếp, dùng làm cận trên loại trừ.
 */
record LeaderboardRange(String period, int offset, LocalDate from, LocalDate to, LocalDate end) {
    static LeaderboardRange of(String period, int offset, LocalDate today) {
        if (LeaderboardPeriod.ALL.equals(period)) {
            return new LeaderboardRange(period, 0, null, null, null);
        }
        LocalDate from = start(period, today, offset);
        LocalDate end = start(period, today, offset - 1);
        return new LeaderboardRange(period, offset, from, end.minusDays(1), end);
    }

    /** Ngày đầu của kỳ lùi back kỳ so với kỳ chứa today (back âm là kỳ sau). */
    static LocalDate start(String period, LocalDate today, int back) {
        return LeaderboardPeriod.WEEK.equals(period)
            ? today.with(DayOfWeek.MONDAY).minusWeeks(back)
            : today.withDayOfMonth(1).minusMonths(back);
    }

    /** Cận dưới (gồm) của kỳ; null nếu không chặn. */
    Instant fromInstant() {
        return instant(from);
    }

    /** Cận trên (loại trừ) của kỳ; null nếu không chặn. */
    Instant endInstant() {
        return instant(end);
    }

    /** Ngày có nằm trong kỳ không; kỳ "tất cả" chứa mọi ngày. */
    boolean contains(LocalDate day) {
        return from == null || (!day.isBefore(from) && !day.isAfter(to));
    }

    private static Instant instant(LocalDate day) {
        return day == null ? null : day.atStartOfDay(LearningActivityService.ZONE).toInstant();
    }
}
