package vn.com.coreacademy.assessment.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.dto.AssessmentViews.CompetencyReview;
import vn.com.coreacademy.assessment.dto.AssessmentViews.MyCompetency;
import vn.com.coreacademy.assessment.repository.CompetencyReviewRepository;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;

/**
 * "Gợi ý ôn tập": với năng lực yếu của chính người gọi, các bài mà câu sai của họ đến từ. Phần trăm là đúng số của
 * {@code me/competency}; dịch vụ này không tự tính điểm, chỉ chọn năng lực dưới ngưỡng và xếp bài.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CompetencyReviewService {
    private final AssessmentReportService reports;
    private final CompetencyReviewRepository reviews;
    private final LearningAssessmentAccess learning;

    /**
     * Năng lực yếu (percent &lt; ngưỡng, bỏ percent rỗng), yếu nhất trước, tối đa {@code limit}; có
     * {@code competencyId} thì chỉ năng lực đó và chỉ khi nó đang yếu. Năng lực không còn bài nào để ôn vẫn được
     * trả về với danh sách bài rỗng.
     */
    public List<CompetencyReview> review(Long competencyId, Integer limit) {
        int max = limit == null ? AssessmentLimits.REVIEW_DEFAULT_COMPETENCIES
            : Math.min(limit, AssessmentLimits.REVIEW_MAX_COMPETENCIES);
        String userId = learning.actor().person().id();
        Instant since = Instant.now().minus(Duration.ofDays(AssessmentLimits.REVIEW_DAYS));

        return reports.myCompetency(null).stream()
            .filter(row -> row.percent() != null && row.percent()
                .compareTo(BigDecimal.valueOf(AssessmentLimits.REVIEW_WEAK_PERCENT)) < 0)
            .filter(row -> competencyId == null || competencyId.equals(row.competencyId()))
            .sorted(Comparator.comparing(MyCompetency::percent).thenComparing(MyCompetency::competencyId))
            .limit(max)
            .map(row -> new CompetencyReview(row.competencyId(), row.name(), row.percent(),
                reviews.lessons(userId, row.competencyId(), since,
                    AssessmentLimits.REVIEW_LESSONS_PER_COMPETENCY)))
            .toList();
    }
}
