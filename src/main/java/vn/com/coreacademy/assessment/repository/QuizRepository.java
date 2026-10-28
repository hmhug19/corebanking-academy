package vn.com.coreacademy.assessment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.assessment.repository.custom.QuizRepositoryCustom;

public interface QuizRepository extends JpaRepository<Quiz, Long>, QuizRepositoryCustom {
}
