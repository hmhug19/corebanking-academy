package vn.com.coreacademy.learning.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.stream.IntStream;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.learning.entity.LearningActivityDay;
import vn.com.coreacademy.learning.repository.LearningActivityDayRepository;
import vn.com.coreacademy.security.SecurityContextUtils;

@Service
@RequiredArgsConstructor
public class LearningWeeklyActivityService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final LearningActivityDayRepository activities;

    @Transactional(readOnly = true)
    public WeeklyActivity get(int weeks) {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate firstMonday = thisMonday.minusWeeks(weeks - 1L);
        List<LocalDate> dates = activities.findByUserIdAndActivityDateBetween(
            SecurityContextUtils.getUserId(), firstMonday, today).stream()
            .map(LearningActivityDay::getActivityDate).distinct().toList();
        List<Week> result = IntStream.range(0, weeks).mapToObj(index -> {
            LocalDate start = firstMonday.plusWeeks(index);
            LocalDate end = start.plusDays(6);
            long count = dates.stream().filter(date -> !date.isBefore(start) && !date.isAfter(end)).count();
            return new Week(start, end, count);
        }).toList();
        return new WeeklyActivity(ZONE.getId(), result);
    }

    public record WeeklyActivity(String timeZone, List<Week> weeks) {
    }

    public record Week(LocalDate weekStart, LocalDate weekEnd, long activeDays) {
    }
}
