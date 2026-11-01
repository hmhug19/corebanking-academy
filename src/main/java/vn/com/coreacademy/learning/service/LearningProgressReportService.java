package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.constants.LearningCodes.Requirement;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningProgressOverview;
import vn.com.coreacademy.learning.dto.LearningProgressOverview.AssessmentOverview;
import vn.com.coreacademy.learning.dto.LearningProgressOverview.AssessmentOverview.LastScore;
import vn.com.coreacademy.learning.dto.LearningProgressOverview.AssessmentSection;
import vn.com.coreacademy.learning.dto.LearningProgressOverview.Availability;
import vn.com.coreacademy.learning.dto.LearningProgressOverview.Counts;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.CourseRepository;
import vn.com.coreacademy.learning.service.LearningQuizDirectory.QuizScore;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LearningProgressReportService {
    private final LearningAccessPolicy access;
    private final CourseRepository courses;
    private final LearningSearchService search;
    private final LearningQuizDirectory quizzes;

    public LearningProgressOverview summary() {
        var actor = access.actor();
        access.requireLearner(actor);
        var unavailable = new Availability("unavailable");
        var base = courses.summary(actor.person().id());
        var counts = new Counts(base.total(), base.completed(), base.inProgress(),
            base.notStarted(), base.overdue(), search.mineTotal(null, true),
            search.mineTotal(Requirement.MANDATORY, null));
        return new LearningProgressOverview(Instant.now(), "Asia/Ho_Chi_Minh", counts, unavailable,
            assessment(actor.person().id(), unavailable), unavailable);
    }

    /**
     * Điểm trên các đề đã xuất bản của khóa hiện tại (cùng tập khóa với counts). Không có đề nào thì unavailable để FE
     * không dựng số 0 giả; có đề mà chưa thi thì available với các điểm null.
     */
    private AssessmentSection assessment(String userId, Availability unavailable) {
        List<QuizScore> scores = quizzes.scores(userId, search.mineCourseIds());
        if (scores.isEmpty()) {
            return unavailable;
        }
        List<BigDecimal> finals = scores.stream().map(QuizScore::finalScore).filter(Objects::nonNull).toList();
        BigDecimal average = finals.isEmpty() ? null : finals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal.valueOf(finals.size()), 2, RoundingMode.HALF_UP);
        return new AssessmentOverview(AssessmentOverview.AVAILABLE, lastScore(scores),
            scores.stream().map(QuizScore::bestScore).filter(Objects::nonNull).max(BigDecimal::compareTo)
                .orElse(null), average, (int) scores.stream().filter(QuizScore::passed).count(), scores.size());
    }

    /** Lượt chấm gần nhất qua mọi đề; cùng thời điểm nộp thì lượt có ID lớn hơn. */
    private static LastScore lastScore(List<QuizScore> scores) {
        return scores.stream().filter(score -> score.last() != null)
            .max(Comparator.comparing((QuizScore score) -> score.last().submittedAt())
                .thenComparing(score -> score.last().attemptId()))
            .map(score -> new LastScore(score.quizId(), score.title(),
                score.courseId(), score.last().attemptId(), score.last().scorePercent(), score.last().passed(),
                score.last().submittedAt()))
            .orElse(null);
    }

    public byte[] export(String q, Long fieldId, Long programId, Long moduleId, String level, String requirement,
                         String learningStatus, String state, Boolean overdue, Boolean assigned, Boolean failed,
                         String sort) {
        var rows = search.export(q, fieldId, programId, moduleId, level, requirement, learningStatus, state,
            overdue, assigned, failed, sort);
        if (rows.getTotalElements() > LearningLimits.MAX_EXPORT_ROWS) {
            throw new BusinessException(LearningResponseCode.EXPORT_TOO_LARGE);
        }
        StringBuilder csv = new StringBuilder("\uFEFFMã khóa,Tên khóa,Lần học,Trạng thái,Tiến độ,Bắt đầu,"
            + "Truy cập gần nhất,Hoàn thành,Hạn,Trạng thái hạn\r\n");
        for (var row : rows) {
            Object[] values = {row.code(), row.title(), row.cycleNo(), row.learningStatus(), row.progressPercent(),
                row.startedAt(), row.lastAccessAt(), row.completedAt(), row.dueAt(), row.deadlineStatus()};
            for (int index = 0; index < values.length; index++) {
                if (index > 0) {
                    csv.append(',');
                }
                csv.append(cell(values[index]));
            }
            csv.append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String cell(Object value) {
        String text = value == null ? "" : value.toString();
        String trimmed = text.stripLeading();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
