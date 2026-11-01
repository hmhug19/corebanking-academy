package vn.com.coreacademy.learning.service;

import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.service.AssessmentDashboardService;
import vn.com.coreacademy.learning.dto.LearningDashboard;

/** Các KPI trong một lần tải dùng cùng snapshot DB; không cache quyền hoặc tải nội dung media. */
@Service
@RequiredArgsConstructor
public class LearningDashboardService {
    private final LearningAnalyticsService learning;
    private final AssessmentDashboardService assessment;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LearningDashboard home() {
        return new LearningDashboard(Instant.now(), LearningAnalyticsService.TIME_ZONE, learning.home(),
            assessment.summary());
    }
}
