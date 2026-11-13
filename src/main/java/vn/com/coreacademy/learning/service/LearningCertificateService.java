package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.learning.dto.CertificateView;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.LearningCertificateRepository;
import vn.com.coreacademy.learning.repository.LearningCertificateRepository.CertificateRow;
import vn.com.coreacademy.learning.repository.LearningRepository;

/**
 * Chứng nhận của người gọi. Chứng nhận là một chu kỳ ghi danh đã hoàn thành: không lưu gì thêm nên không có gì để cấp
 * hay thu hồi, còn việc hoàn thành vẫn do LearningProgressService quyết định. Ghi danh không phải của người gọi, chưa
 * hoàn thành hoặc không tồn tại đều trả LEARNING-NOT_FOUND, để câu trả lời không lộ ra là trường hợp nào.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningCertificateService {
    private final LearningCertificateRepository certificates;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningQuizDirectory quizzes;
    private final LearningRepository records;

    /**
     * Các chu kỳ đã hoàn thành của người gọi, hoàn thành gần nhất trước. Họ tên và điểm được điền một lần cho mỗi
     * trang.
     */
    public PageResponse<CertificateView> list(int page, int size) {
        String userId = learnerId();
        long total = certificates.count(userId);
        long offset = (long) page * size;
        List<CertificateRow> rows = offset >= total ? List.of() : certificates.page(userId, (int) offset, size);
        return PageResponse.from(new PageImpl<>(views(userId, rows), PageRequest.of(page, size), total));
    }

    public CertificateView get(Long enrollmentId) {
        String userId = learnerId();
        CertificateRow row = certificates.find(userId, enrollmentId)
            .orElseThrow(() -> new BusinessException(LearningResponseCode.NOT_FOUND));
        return views(userId, List.of(row)).get(0);
    }

    private List<CertificateView> views(String userId, List<CertificateRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        String fullName = identity.fullNames(List.of(userId)).get(userId);
        Map<Long, Long> exams = new HashMap<>();
        var selected = records.finalQuizIds(rows.stream().map(CertificateRow::revisionId).distinct().toList());
        for (CertificateRow row : rows) {
            Long quizId = selected.get(row.revisionId());
            if (quizId != null) {
                exams.put(row.enrollmentId(), quizId);
            }
        }
        Map<Long, BigDecimal> finalExamBest = quizzes.enrollmentScores(userId, exams);
        return rows.stream().map(row -> new CertificateView(row.enrollmentId(), row.courseId(), row.courseCode(),
            row.courseTitle(), row.cycleNo(), row.revisionId(), row.userId(), fullName, row.completedAt(),
            finalExamBest.get(row.enrollmentId()), verificationCode(row))).toList();
    }

    private static String verificationCode(CertificateRow row) {
        return "CBA-%s-%s-%d".formatted(row.courseCode(), row.userId(), row.cycleNo()).toUpperCase(Locale.ROOT);
    }

    private String learnerId() {
        var actor = access.actor();
        access.requireLearner(actor);
        return actor.person().id();
    }
}
