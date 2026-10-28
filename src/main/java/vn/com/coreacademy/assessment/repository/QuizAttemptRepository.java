package vn.com.coreacademy.assessment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.repository.custom.QuizAttemptRepositoryCustom;

public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long>, QuizAttemptRepositoryCustom {
}
