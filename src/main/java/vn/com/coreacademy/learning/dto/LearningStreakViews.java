package vn.com.coreacademy.learning.dto;

import java.time.LocalDate;
import java.util.List;

public final class LearningStreakViews {
    private LearningStreakViews() {
    }

    /**
     * Một ngày của lịch nhiệt (ngày Asia/Ho_Chi_Minh): số bài học hoàn thành, số bài kiểm tra đã nộp và cờ ngày nghỉ
     * (cuối tuần hoặc ngày nghỉ của lịch chung, trừ ngày làm bù).
     */
    public record HeatmapDay(LocalDate date, int lessons, int quizzes, boolean restDay) {
    }

    /**
     * Chuỗi ngày học của người gọi tính theo ngày làm việc. days phủ 26 tuần đến hết hôm nay, bắt đầu vào thứ Hai,
     * mỗi ngày một dòng kể cả ngày không học.
     */
    public record MyStreak(int currentStreak, int longestStreak, List<HeatmapDay> days) {
    }
}
