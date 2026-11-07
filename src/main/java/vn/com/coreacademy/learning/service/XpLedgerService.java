package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.learning.config.LearningPointsProperties;
import vn.com.coreacademy.learning.constants.LearningCodes.XpSource;
import vn.com.coreacademy.learning.dto.GamificationConfig;
import vn.com.coreacademy.learning.repository.XpLedgerRepository;

/**
 * Ghi XP vào sổ chỉ cộng thêm. Mỗi lần ghi chạy trong giao dịch của sự kiện sinh ra nó (bài học xong, đạt đề, xong
 * khóa) và dùng phiên bản cấu hình đang áp dụng lúc đó. Mỗi (người, loại nguồn, mã nguồn) chỉ có một dòng nên học lại
 * hay lặp sự kiện không ghi thêm; số XP bằng 0 không ghi dòng nào.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class XpLedgerService {
    static final int DEFAULT_FIRST_TRY_THRESHOLD_PERCENT = 90;
    static final int DEFAULT_FIRST_TRY_BONUS_XP = 20;
    static final int DEFAULT_COMMUNITY_XP = 10;
    static final int DEFAULT_COMMUNITY_DAILY_CAP = 30;

    private final XpLedgerRepository repository;
    private final LearningPointsProperties seed;
    private final LevelService levels;

    /** Phiên bản cấu hình đang áp dụng; lần đầu tiên được gieo từ app.learning.points.*. */
    public GamificationConfig currentConfig() {
        return repository.latestConfig().orElseGet(() -> {
            repository.seedFirstConfig(seed.getLesson(), seed.getQuizPass(), DEFAULT_FIRST_TRY_BONUS_XP,
                DEFAULT_FIRST_TRY_THRESHOLD_PERCENT, DEFAULT_COMMUNITY_XP, DEFAULT_COMMUNITY_DAILY_CAP);
            return repository.latestConfig().orElseThrow();
        });
    }

    /** Bài học hoàn thành; lessonKey là định danh bài gốc để học lại ở bản khác của khóa không được tính thêm. */
    public void lessonCompleted(String userId, Long lessonKey, Instant at) {
        GamificationConfig config = currentConfig();
        append(userId, config.lessonXp(), at, XpSource.LESSON, String.valueOf(lessonKey), config);
    }

    /**
     * Lượt thi đạt: XP đạt đề một lần cho mỗi đề, cộng thưởng nếu đây là lượt đầu tiên của người này trên đề và điểm
     * từ ngưỡng trở lên.
     */
    public void quizPassed(String userId, Long quizId, int attemptNo, BigDecimal scorePercent, Instant at) {
        GamificationConfig config = currentConfig();
        append(userId, config.quizPassXp(), at, XpSource.QUIZ_PASS, String.valueOf(quizId), config);
        boolean firstTry = attemptNo == 1 && scorePercent != null
            && scorePercent.compareTo(BigDecimal.valueOf(config.firstTryThresholdPercent())) >= 0;
        if (firstTry) {
            append(userId, config.firstTryBonusXp(), at, XpSource.QUIZ_FIRST_TRY_BONUS, String.valueOf(quizId),
                config);
        }
    }

    /** Khóa học hoàn thành; XP là điểm thưởng riêng của khóa, mỗi khóa một lần dù học lại. */
    public void courseCompleted(String userId, Long courseId, int rewardPoints, Instant at) {
        append(userId, rewardPoints, at, XpSource.COURSE, String.valueOf(courseId), currentConfig());
    }

    /**
     * Đóng góp cộng đồng được chấp nhận hoặc hữu ích của một nội dung (hoặc mọi nội dung khi contentId null): mỗi
     * đóng góp một dòng với XP cộng đồng của phiên bản đang áp dụng, mỗi người tối đa trần ngày.
     */
    public int communityContributions(Long contentId) {
        GamificationConfig config = currentConfig();
        if (config.communityXp() <= 0 || config.communityDailyCap() <= 0) {
            return 0;
        }
        return repository.awardCommunity(contentId, config.communityXp(), config.communityDailyCap(),
            config.versionId());
    }

    private void append(String userId, int amount, Instant at, String kind, String ref, GamificationConfig config) {
        if (amount > 0) {
            if (repository.append(userId, amount, at, kind, ref, config.versionId(), null)) {
                levels.refresh(userId);
            }
        }
    }
}
