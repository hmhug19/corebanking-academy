package vn.com.coreacademy.assessment.repository.custom;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.com.coreacademy.assessment.dto.AssessmentViews;

public interface QuestionRepositoryCustom {
    /** programId hoặc courseId phải được service kiểm quyền trước khi truy vấn. */
    record Filter(Long courseId, String q, Long lessonId, Long competencyId, String questionType, String difficulty,
                  String status, Long programId) {
        public Filter(Long courseId, String q, Long lessonId, Long competencyId, String questionType,
                      String difficulty, String status) {
            this(courseId, q, lessonId, competencyId, questionType, difficulty, status, null);
        }
    }

    Page<AssessmentViews.QuestionSummary> search(Filter filter, Pageable page);
}
