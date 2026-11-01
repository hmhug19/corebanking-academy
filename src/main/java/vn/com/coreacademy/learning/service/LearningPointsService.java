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
import vn.com.coreacademy.learning.config.LearningPointsProperties;
import vn.com.coreacademy.learning.dto.LearningPointsViews.MyPoints;
import vn.com.coreacademy.learning.dto.LearningPointsViews.PointParts;
import vn.com.coreacademy.learning.repository.LearningPointsRepository;

/**
 * Điểm học tập tính khi đọc từ tiến độ, lượt thi đạt, điểm thưởng của khóa và chuỗi ngày học, nên không bao giờ lệch
 * khỏi dữ liệu gốc. Bốn phần: bài học hoàn thành, bài kiểm tra đạt, khóa hoàn thành và chuỗi ngày học. Quy tắc và
 * trọng số nằm ở server; client chỉ hiển thị.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningPointsService {
    private final LearningPointsProperties weights;
    private final LearningPointsRepository repository;
    private final LearningQuizDirectory quizzes;
    private final LearningAccessPolicy access;

    /** Điểm của caller; chỉ học viên (LEARNER, OWN) có điểm. */
    public MyPoints mine() {
        var actor = access.actor();
        access.requireLearner(actor);
        String userId = actor.person().id();
        return scores(List.of(userId)).get(userId);
    }

    /** Điểm của mọi người trong userIds, đủ từng người kể cả khi chưa có điểm nào. */
    public Map<String, MyPoints> scores(Collection<String> userIds) {
        return scores(userIds, LearningActivityService.localDate(Instant.now()));
    }

    /** today là ngày Asia/Ho_Chi_Minh của bên gọi; chuỗi tính từ ngày học gần nhất nếu đó là hôm nay hoặc hôm qua. */
    public Map<String, MyPoints> scores(Collection<String> userIds, LocalDate today) {
        Map<String, Long> lessons = repository.completedLessons(userIds);
        Map<String, Integer> passed = quizzes.passedQuizCounts(userIds);
        Map<String, Long> rewards = repository.courseRewards(userIds);
        Map<String, Long> streaks = repository.streakDays(userIds, today);
        Map<String, MyPoints> result = new HashMap<>();
        for (String userId : userIds) {
            int streakDays = Math.toIntExact(streaks.getOrDefault(userId, 0L));
            PointParts parts = new PointParts(
                (long) weights.getLesson() * lessons.getOrDefault(userId, 0L),
                (long) weights.getQuizPass() * passed.getOrDefault(userId, 0),
                rewards.getOrDefault(userId, 0L),
                (long) weights.getStreakDay() * Math.min(streakDays, weights.getStreakCap()));
            result.put(userId, new MyPoints(parts.lessons() + parts.quizzes() + parts.courses() + parts.streak(),
                parts, streakDays));
        }
        return result;
    }
}
