package vn.com.coreacademy.learning.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.learning.constants.LearningCodes.XpSource;
import vn.com.coreacademy.learning.dto.LearningPointsViews.MyPoints;
import vn.com.coreacademy.learning.dto.LearningPointsViews.PointParts;
import vn.com.coreacademy.learning.repository.LearningPointsRepository;

/**
 * XP của người học đọc từ sổ ghi XP chỉ cộng thêm, nên đổi trọng số, xóa khóa hay đổi nội dung không làm XP đã nhận
 * thay đổi. Bốn phần: bài học, bài kiểm tra đạt, thưởng đạt ngay lần đầu và khóa học; chuỗi ngày không cộng XP. Quy
 * tắc nằm ở server; client chỉ hiển thị.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningPointsService {
    private final LearningPointsRepository repository;
    private final LearningStreakService streaks;
    private final LearningAccessPolicy access;

    /** XP của caller; chỉ học viên (LEARNER, OWN) có XP. */
    public MyPoints mine() {
        var actor = access.actor();
        access.requireLearner(actor);
        String userId = actor.person().id();
        return scores(List.of(userId)).get(userId);
    }

    /** XP của mọi người trong userIds, đủ từng người kể cả khi chưa có XP nào. */
    public Map<String, MyPoints> scores(Collection<String> userIds) {
        return scores(userIds, LearningActivityService.localDate(Instant.now()));
    }

    /** today là ngày Asia/Ho_Chi_Minh của bên gọi, dùng cho chuỗi ngày làm việc hiển thị cạnh XP. */
    public Map<String, MyPoints> scores(Collection<String> userIds, LocalDate today) {
        Map<String, Map<String, Long>> sums = repository.sumsByKind(userIds);
        Map<String, Integer> streakDays = streaks.currentStreaks(userIds, today);
        Map<String, MyPoints> result = new HashMap<>();
        for (String userId : userIds) {
            Map<String, Long> own = sums.getOrDefault(userId, Map.of());
            PointParts parts = new PointParts(own.getOrDefault(XpSource.LESSON, 0L),
                own.getOrDefault(XpSource.QUIZ_PASS, 0L), own.getOrDefault(XpSource.QUIZ_FIRST_TRY_BONUS, 0L),
                own.getOrDefault(XpSource.COURSE, 0L));
            result.put(userId, new MyPoints(parts.lessons() + parts.quizzes() + parts.firstTryBonus()
                + parts.courses(), parts, streakDays.getOrDefault(userId, 0)));
        }
        return result;
    }
}
