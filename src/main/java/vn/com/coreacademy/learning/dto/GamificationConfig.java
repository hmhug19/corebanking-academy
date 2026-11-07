package vn.com.coreacademy.learning.dto;

/**
 * Một phiên bản cấu hình XP đang áp dụng. Bất biến: đổi cấu hình là thêm phiên bản mới, nên dòng sổ XP cũ giữ nguyên
 * số XP đã ghi.
 */
public record GamificationConfig(long versionId, int versionNo, int lessonXp, int quizPassXp, int firstTryBonusXp,
                                 int firstTryThresholdPercent, int communityXp, int communityDailyCap) {
}
