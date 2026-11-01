package vn.com.coreacademy.learning.dto;

import vn.com.coreacademy.common.dto.PageResponse;

public final class LearningPointsViews {
    private LearningPointsViews() {
    }

    public record PointParts(long lessons, long quizzes, long courses, long streak) {
    }

    /** Điểm học tập của một người; total luôn bằng tổng bốn phần, streakDays là chuỗi chưa bị chặn bởi trần. */
    public record MyPoints(long total, PointParts parts, int streakDays) {
    }

    /**
     * Một dòng bảng xếp hạng. rank dùng hạng đồng điểm (1, 1, 3); null chỉ khi người gọi không thuộc phạm vi đang xem
     * (xem nhóm khi chưa có nhóm). teamName có thể null khi người học chưa thuộc nhóm.
     */
    public record LeaderboardRow(Integer rank, String userId, String fullName, String teamName, long points,
                                 int streakDays, boolean me) {
    }

    /** page là trang xếp hạng; me là dòng của người gọi, luôn có kể cả khi không nằm trong trang. */
    public record LeaderboardPage(PageResponse<LeaderboardRow> page, LeaderboardRow me) {
    }
}
