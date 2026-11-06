package vn.com.coreacademy.assessment.repository;

import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.repository.custom.QuizAttemptRepositoryCustom;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long>, QuizAttemptRepositoryCustom {
    boolean existsByUserIdAndEnrollmentIdAndQuizIdAndStatusIn(String userId, Long enrollmentId,
                                                              Long quizId, Collection<String> statuses);

    boolean existsByUserIdAndEnrollmentIdAndQuizIdAndPassedTrueAndStatusIn(String userId, Long enrollmentId,
                                                                           Long quizId, Collection<String> statuses);
}
