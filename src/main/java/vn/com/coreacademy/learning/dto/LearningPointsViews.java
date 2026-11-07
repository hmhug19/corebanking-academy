package vn.com.coreacademy.learning.dto;

import vn.com.coreacademy.common.dto.PageResponse;

public final class LearningPointsViews {
    private LearningPointsViews() {
    }

    /** Các nguồn XP; chuỗi ngày không cộng XP nên không có phần chuỗi. */
    public record PointParts(long lessons, long quizzes, long firstTryBonus, long courses, long community) {
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

    /** page là trang xếp hạng; me là dòng của người gọi, luôn có kể cả khi không nằm trong trang. */
    public record LeaderboardPage(PageResponse<LeaderboardRow> page, LeaderboardRow me) {
    }
}
