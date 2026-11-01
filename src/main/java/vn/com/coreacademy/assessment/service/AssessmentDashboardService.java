package vn.com.coreacademy.assessment.service;

import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ScoreMethod;
import vn.com.coreacademy.assessment.dto.AssessmentDashboard;
import vn.com.coreacademy.assessment.repository.custom.AssessmentDashboardRepository;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;

/** Adapter đọc kết quả thi cho trang chủ; định danh luôn lấy từ context, không nhận userId từ FE. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AssessmentDashboardService {
    private final LearningAssessmentAccess learning;
    private final AssessmentDashboardRepository repository;

    public AssessmentDashboard.Summary summary() {
        String userId = learning.requireLearnerActor().person().id();
        return new AssessmentDashboard.Summary(repository.counts(userId), repository.score(userId, false),
            repository.score(userId, true));
    }

    public PageResponse<AssessmentDashboard.Competency> competencies(String method, int page, int size) {
        String userId = learning.requireLearnerActor().person().id();
        if (!Set.of(ScoreMethod.BEST, ScoreMethod.LAST).contains(method)) {
            throw BusinessException.validation("method", "INVALID_VALUE", "Chọn BEST hoặc LAST.");
        }
        return PageResponse.from(repository.competencies(userId, method, PageQuery.of(page, size, "id", Set.of("id"))));
    }
}
