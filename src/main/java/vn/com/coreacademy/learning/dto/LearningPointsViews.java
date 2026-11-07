package vn.com.coreacademy.learning.dto;

import java.util.List;

public final class LearningPointsViews {
    private LearningPointsViews() {
    }

    /** Các nguồn XP; chuỗi ngày không cộng XP nên không có phần chuỗi. */
    public record PointParts(long lessons, long quizzes, long firstTryBonus, long courses, long community,
                             long adjustments) {
    }

    /**
     * XP của một người; total luôn bằng tổng các phần. streakDays là chuỗi hiện tại tính theo ngày làm việc, chỉ để
     * hiển thị.
     */
    public record MyPoints(long total, PointParts parts, int streakDays) {
    }

    /**
     * Một dòng bảng xếp hạng. rank dùng hạng đồng điểm (1, 1, 3); null chỉ khi người gọi không thuộc phạm vi đang xem
     * (xem nhóm khi chưa có nhóm). teamName có thể null khi người học chưa thuộc nhóm.
     */
    public record LeaderboardRow(Integer rank, String userId, String fullName, String teamName, long points,
                                 int streakDays, boolean me) {
    }

    /**
     * Bảng xếp hạng một kỳ. top là tối đa 10 người đầu; me là dòng của người gọi, luôn có (rank null nếu không thuộc
     * phạm vi). Khi me nằm ngoài top, above là 1-2 người ngay trên me và gapToAbove là số XP còn thiếu để vượt người
     * gần nhất có XP cao hơn (null khi không có). Không có phân trang và không có tổng số người. from và to là ngày
     * đầu và ngày cuối của kỳ (null khi period=all).
     */
    public record Leaderboard(String period, int offset, java.time.LocalDate from, java.time.LocalDate to,
                              List<LeaderboardRow> top, LeaderboardRow me, List<LeaderboardRow> above,
                              Long gapToAbove) {
    }
}
