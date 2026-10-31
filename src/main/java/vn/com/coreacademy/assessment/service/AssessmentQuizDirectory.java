package vn.com.coreacademy.assessment.service;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.repository.QuizRepository;
import vn.com.coreacademy.learning.service.LearningQuizDirectory;

/** Cài đặt cổng của Learning: tra đề theo ID và trả khóa sở hữu, trạng thái, cờ xóa. */
@Component
@RequiredArgsConstructor
public class AssessmentQuizDirectory implements LearningQuizDirectory {
    private final QuizRepository quizzes;

    @Override
    @Transactional(readOnly = true)
    public Optional<QuizRef> find(Long quizId) {
        return quizzes.findById(quizId)
            .map(q -> new QuizRef(q.getId(), q.getCourseId(), q.getStatus(), Boolean.TRUE.equals(q.getDeleted())));
    }
}
