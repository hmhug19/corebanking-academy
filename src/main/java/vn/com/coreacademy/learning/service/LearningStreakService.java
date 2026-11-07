package vn.com.coreacademy.learning.service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.learning.dto.LearningStreakViews.HeatmapDay;
import vn.com.coreacademy.learning.dto.LearningStreakViews.MyStreak;
import vn.com.coreacademy.learning.repository.LearningStreakRepository;

/**
 * Chuỗi ngày học theo ngày làm việc và dữ liệu lịch nhiệt 26 tuần. Quy tắc nằm ở server; client chỉ đổi số bài thành
 * độ đậm. Khi đứt chuỗi không có thông báo nào, chuỗi chỉ về số mới.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningStreakService {
    /** Số tuần của lịch nhiệt. */
    static final int HEATMAP_WEEKS = 26;

    private final LearningStreakRepository repository;
    private final LearningAccessPolicy access;

    /** Chuỗi của caller; chỉ học viên (LEARNER, OWN). */
    public MyStreak mine() {
        var actor = access.actor();
        access.requireLearner(actor);
        return streak(actor.person().id(), LearningActivityService.localDate(Instant.now()));
    }

    /** Chuỗi hiện tại của nhiều người, mỗi người đủ một giá trị (0 khi chưa có ngày nào); today là ngày VN của bên gọi. */
    public Map<String, Integer> currentStreaks(Collection<String> userIds, LocalDate today) {
        Map<String, Set<LocalDate>> days = repository.qualifyingDays(userIds);
        Map<LocalDate, String> calendar = repository.calendar();
        Map<String, Integer> result = new HashMap<>();
        for (String userId : userIds) {
            result.put(userId, WorkingDayStreak.compute(days.getOrDefault(userId, Set.of()), calendar, today)
                .current());
        }
        return result;
    }

    /** today là ngày Asia/Ho_Chi_Minh của bên gọi. */
    public MyStreak streak(String userId, LocalDate today) {
        Map<LocalDate, Integer> lessons = repository.lessonsPerDay(userId);
        Map<LocalDate, Integer> quizzes = repository.quizzesPerDay(userId);
        Map<LocalDate, String> calendar = repository.calendar();

        Set<LocalDate> qualifying = new HashSet<>();
        qualifying.addAll(lessons.keySet());
        qualifying.addAll(quizzes.keySet());
        var result = WorkingDayStreak.compute(qualifying, calendar, today);

        LocalDate start = today.minusWeeks(HEATMAP_WEEKS - 1L).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<HeatmapDay> days = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            days.add(new HeatmapDay(day, lessons.getOrDefault(day, 0), quizzes.getOrDefault(day, 0),
                !WorkingDayStreak.isWorkingDay(day, calendar)));
        }
        return new MyStreak(result.current(), result.longest(), days);
    }
}
