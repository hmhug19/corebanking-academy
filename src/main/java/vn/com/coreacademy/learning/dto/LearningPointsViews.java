package vn.com.coreacademy.learning.dto;

public final class LearningPointsViews {
    private LearningPointsViews() {
    }

    public record PointParts(long lessons, long quizzes, long courses, long streak) {
    }

    /** Điểm học tập của một người; total luôn bằng tổng bốn phần, streakDays là chuỗi chưa bị chặn bởi trần. */
    public record MyPoints(long total, PointParts parts, int streakDays) {
    }
}
