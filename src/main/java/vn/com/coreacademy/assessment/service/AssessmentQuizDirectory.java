package vn.com.coreacademy.assessment.service;

import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.repository.QuizAttemptRepository;
import vn.com.coreacademy.assessment.repository.QuizRepository;
import vn.com.coreacademy.learning.service.LearningQuizDirectory;

/**
 * Cài đặt cổng của Learning: tra đề theo ID (khóa sở hữu, trạng thái, cờ xóa) và tập khóa mà học viên đã thi cuối
 * khóa nhưng chưa đạt.
 */
@Component
@RequiredArgsConstructor
public class AssessmentQuizDirectory implements LearningQuizDirectory {
    private final QuizRepository quizzes;
    private final QuizAttemptRepository attempts;

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
}
