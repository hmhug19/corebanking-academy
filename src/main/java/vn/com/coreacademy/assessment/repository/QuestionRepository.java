package vn.com.coreacademy.assessment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.assessment.entity.Question;
import vn.com.coreacademy.assessment.repository.custom.QuestionRepositoryCustom;

public interface QuestionRepository extends JpaRepository<Question, Long>, QuestionRepositoryCustom {
}
