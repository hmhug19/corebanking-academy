package vn.com.coreacademy.assessment.repository.custom;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.com.coreacademy.assessment.dto.AssessmentViews;

public interface QuizRepositoryCustom {
    /** courseId bắt buộc và đã được service kiểm quyền quản lý; các filter khác là tùy chọn. */
    record Filter(Long courseId, String q, String status, String quizType) {
    }

    Page<AssessmentViews.QuizSummary> search(Filter filter, Pageable page);
}
