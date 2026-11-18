package vn.com.coreacademy.assessment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizStatus;
import vn.com.coreacademy.assessment.dto.AssessmentRequests.VersionRequest;
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
    private final ObjectProvider<QuizService> quizService;

    @Override
    public void publishDraftLessonQuiz(Long courseId, Long quizId) {
        quizzes.findById(quizId)
            .filter(quiz -> courseId.equals(quiz.getCourseId()) && !Boolean.TRUE.equals(quiz.getDeleted())
                && QuizStatus.DRAFT.equals(quiz.getStatus()))
            .ifPresent(quiz -> quizService.getObject().publish(quizId, new VersionRequest(quiz.getRowVersion())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QuizRef> find(Long quizId) {
        return quizzes.findById(quizId)
            .map(q -> new QuizRef(q.getId(), q.getCourseId(), q.getStatus(), Boolean.TRUE.equals(q.getDeleted()),
                q.getQuizType(), q.getPassPercent(), q.getMaxAttempts(),
                Boolean.TRUE.equals(q.getAllowRetakeAfterPass())));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPassed(String userId, Long enrollmentId, Long quizId) {
        return attempts.existsByUserIdAndEnrollmentIdAndQuizIdAndPassedTrueAndStatusIn(userId, enrollmentId, quizId,
            List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasGradedAttempt(String userId, Long enrollmentId, Long quizId) {
        return attempts.existsByUserIdAndEnrollmentIdAndQuizIdAndStatusIn(userId, enrollmentId, quizId,
            List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED));
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> failedFinalExamCourseIds(String userId) {
        return attempts.failedFinalExamCourseIds(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Integer> passedQuizCounts(Collection<String> userIds) {
        return attempts.passedQuizCounts(userIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> enrollmentScores(String userId, Map<Long, Long> quizIdsByEnrollment) {
        Map<Long, BigDecimal> result = new HashMap<>();
        for (QuizAttempt attempt : repository.gradedEnrollmentAttempts(userId, quizIdsByEnrollment.keySet())) {
            if (attempt.getQuizId().equals(quizIdsByEnrollment.get(attempt.getEnrollmentId()))
                && attempt.getScorePercent() != null) {
                result.merge(attempt.getEnrollmentId(), attempt.getScorePercent(), BigDecimal::max);
            }
        }
        return result;
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
