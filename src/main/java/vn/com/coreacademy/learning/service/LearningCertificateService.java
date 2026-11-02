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

/**
 * Certificates of the caller. A certificate is a completed enrollment cycle: nothing is stored, so there is nothing
 * to issue or revoke, and completion itself stays decided by LearningProgressService. An enrollment that is not the
 * caller's, is not completed or does not exist is LEARNING-NOT_FOUND, so the answer does not reveal which.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningCertificateService {
    private final LearningCertificateRepository certificates;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningQuizDirectory quizzes;

    /** Completed cycles of the caller, newest completion first. Name and scores are filled once per page. */
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
        Map<Long, BigDecimal> finalExamBest = new HashMap<>();
        quizzes.scores(userId, rows.stream().map(CertificateRow::courseId).distinct().toList()).stream()
            .filter(LearningQuizDirectory.QuizScore::finalExam)
            .forEach(score -> finalExamBest.put(score.courseId(), score.bestScore()));
        return rows.stream().map(row -> new CertificateView(row.enrollmentId(), row.courseId(), row.courseCode(),
            row.courseTitle(), row.cycleNo(), row.revisionId(), row.userId(), fullName, row.completedAt(),
            finalExamBest.get(row.courseId()), verificationCode(row))).toList();
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
