package vn.com.coreacademy.assessment.repository.custom;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.com.coreacademy.assessment.dto.AssessmentViews;

public interface QuestionRepositoryCustom {
    /** courseId bắt buộc và đã được service kiểm quyền quản lý; các filter khác là tùy chọn. */
    record Filter(Long courseId, String q, Long lessonId, Long competencyId, String questionType, String difficulty,
                  String status) {
    }

    Page<AssessmentViews.QuestionSummary> search(Filter filter, Pageable page);
}
