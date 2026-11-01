package vn.com.coreacademy.assessment.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.dto.AssessmentViews.AttemptSummary;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.assessment.repository.QuizAttemptRepository;
import vn.com.coreacademy.assessment.repository.QuizRepository;
import vn.com.coreacademy.learning.service.LearningQuizDirectory;

/**
 * Cài đặt cổng của Learning: tra đề theo ID (khóa sở hữu, trạng thái, cờ xóa), tập khóa mà học viên đã thi cuối
 * khóa nhưng chưa đạt và điểm của học viên trên các đề của một nhóm khóa.
 */
@Component
@RequiredArgsConstructor
public class AssessmentQuizDirectory implements LearningQuizDirectory {
    private final QuizRepository quizzes;
    private final QuizAttemptRepository attempts;
    private final AssessmentRepository repository;

    @Override
    @Transactional(readOnly = true)
    public Optional<QuizRef> find(Long quizId) {
        return quizzes.findById(quizId)
            .map(q -> new QuizRef(q.getId(), q.getCourseId(), q.getStatus(), Boolean.TRUE.equals(q.getDeleted())));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> failedFinalExamCourseIds(String userId) {
        return attempts.failedFinalExamCourseIds(userId);
    }

    /** Dùng AttemptSummaries như màn giới thiệu đề nên điểm trên tổng quan và trên đề không thể lệch nhau. */
    @Override
    @Transactional(readOnly = true)
    public List<QuizScore> scores(String userId, Collection<Long> courseIds) {
        List<Quiz> quizzes = repository.publishedQuizzes(courseIds);
        Map<Long, List<QuizAttempt>> attemptsByQuiz = repository
            .gradedAttempts(quizzes.stream().map(Quiz::getId).toList(), userId).stream()
            .collect(Collectors.groupingBy(QuizAttempt::getQuizId));
        Instant now = ServerTime.now();
        return quizzes.stream().map(quiz -> {
            List<QuizAttempt> graded = attemptsByQuiz.getOrDefault(quiz.getId(), List.of());
            AttemptSummary summary = AttemptSummaries.summarize(quiz, graded, 0, now);
            QuizAttempt last = AttemptSummaries.lastGraded(graded);
            return new QuizScore(quiz.getId(), quiz.getCourseId(), quiz.getTitle(), quiz.getQuizType(),
                summary.bestScore(), summary.finalScore(), Boolean.TRUE.equals(summary.passed()),
                last == null ? null : new LastAttempt(last.getId(), last.getScorePercent(), last.getPassed(),
                    last.getSubmittedAt()));
        }).toList();
    }
}
