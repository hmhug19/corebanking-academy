package vn.com.coreacademy.assessment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.LearnerStatus;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.repository.AssessmentReportRepository;
import vn.com.coreacademy.assessment.repository.AssessmentReportRepository.Criteria;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess.ReportScope;

/**
 * Báo cáo Quiz Engine chỉ đọc, tính từ dữ liệu gốc trong phạm vi của caller (SUPER_ADMIN/ADMIN/INSTRUCTOR/
 * REPORT_VIEWER theo GLOBAL/ORG gồm cây con/TEAM). Bộ lọc đơn vị/nhóm chỉ thu hẹp thêm, không mở rộng phạm vi.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AssessmentReportService {
    public static final Set<String> TYPES = Set.of("results", "learners", "competency", "questions");
    private static final Set<String> RESULT_SORTS = Set.of("id", "startedAt", "submittedAt", "scorePercent");
    private static final Set<String> LEARNER_SORTS = Set.of("userId", "finalScore", "lastSubmittedAt");
    private static final Set<String> COMPETENCY_SORTS = Set.of("groupKey", "competencyId");
    private static final Set<String> QUESTION_SORTS = Set.of("questionId", "timesServed", "wrongPercent");
    private static final Set<String> ATTEMPT_STATUSES = Set.of(AttemptStatus.IN_PROGRESS, AttemptStatus.SUBMITTED,
        AttemptStatus.AUTO_SUBMITTED, AttemptStatus.VOIDED);
    private static final Set<String> LEARNER_STATUSES = Set.of(LearnerStatus.NOT_STARTED, LearnerStatus.IN_PROGRESS,
        LearnerStatus.FAILED, LearnerStatus.PASSED);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final LearningAssessmentAccess learning;
    private final AssessmentReportRepository reports;
    private final AssessmentRepository repository;

    /** Bộ lọc chung; from/to theo thời điểm nộp bài, to không bao gồm. */
    public record Filter(Long orgId, Long teamId, Long courseId, Long quizId, Instant from, Instant to) {
    }

    public AssessmentViews.ReportOverview overview(Filter filter) {
        Criteria criteria = criteria(filter, null);
        AssessmentViews.ReportTotals totals = reports.totals(criteria);
        List<AssessmentViews.ReportGroupData> byOrg = reports.groups(criteria, false);
        Map<Long, String> orgNames = learning.orgNames(byOrg.stream().map(AssessmentViews.ReportGroupData::id)
            .filter(Objects::nonNull).toList());
        return new AssessmentViews.ReportOverview(totals.gradedAttempts(), totals.learners(), totals.passedLearners(),
            rate(totals.passedLearners(), totals.learners()), totals.averageScore(), totals.inProgressAttempts(),
            reports.groups(criteria, true).stream().map(g -> group(g, g.name())).toList(),
            byOrg.stream().map(g -> group(g, orgNames.get(g.id()))).toList());
    }

    public PageResponse<AssessmentViews.ReportResultRow> results(Filter filter, String userId, String status,
                                                                 Boolean passed, int page, int size, String sort) {
        return PageResponse.from(results(filter, userId, status, passed, PageQuery.of(page, size, sort,
            RESULT_SORTS)));
    }

    public PageResponse<AssessmentViews.ReportLearnerRow> learners(Filter filter, String learnerStatus, int page,
                                                                   int size, String sort) {
        return PageResponse.from(learners(filter, learnerStatus, PageQuery.of(page, size, sort, LEARNER_SORTS)));
    }

    public PageResponse<AssessmentViews.ReportCompetencyRow> competency(Filter filter, String groupBy, int page,
                                                                        int size, String sort) {
        return PageResponse.from(competency(filter, groupBy, PageQuery.of(page, size, sort, COMPETENCY_SORTS)));
    }

    public PageResponse<AssessmentViews.QuestionStatRow> questions(Filter filter, int page, int size, String sort) {
        return PageResponse.from(reports.questions(criteria(filter, null), PageQuery.of(page, size, sort,
            QUESTION_SORTS)));
    }

    /** Năng lực của chính caller trên mọi lượt đã chấm (tùy chọn theo khóa); không cần quyền báo cáo. */
    public List<AssessmentViews.MyCompetency> myCompetency(Long courseId) {
        String userId = learning.actor().person().id();
        Criteria criteria = new Criteria(true, Set.of(), Set.of(), null, null, courseId, null, userId, null, null);
        return reports.competency(criteria, true, PageRequest.of(0, AssessmentLimits.MAX_EXPORT_ROWS,
                PageQuery.of(0, 1, "competencyId", COMPETENCY_SORTS).getSort())).getContent().stream()
            .map(r -> new AssessmentViews.MyCompetency(r.competencyId(), r.code(), r.name(), r.totalQuestions(),
                r.correctCount(), r.maxScore(), r.score(), percent(r.score(), r.maxScore())))
            .toList();
    }

    /** CSV cùng bộ lọc với danh sách tương ứng, tối đa MAX_EXPORT_ROWS dòng. */
    public byte[] export(String type, Filter filter, String userId, String status, Boolean passed, String groupBy,
                         String learnerStatus, String sort) {
        return switch (type) {
            case "results" -> {
                var rows = results(filter, userId, status, passed, exportPage(sort, "submittedAt,desc",
                    RESULT_SORTS)).getContent();
                yield Csv.write(List.of("Mã lượt", "Bài kiểm tra", "Mã cán bộ", "Họ tên", "Đơn vị", "Nhóm", "Lượt",
                    "Trạng thái", "Cách nộp", "Bắt đầu", "Nộp lúc", "Số câu đúng", "Tổng số câu", "Điểm %", "Đạt"),
                    limit(rows).stream().map(r -> new Object[] {r.attemptId(), r.quizTitle(), r.userId(),
                        r.userName(), r.orgName(), r.teamName(), r.attemptNo(), r.status(), r.submitType(),
                        r.startedAt(), r.submittedAt(), r.correctCount(), r.totalQuestions(), r.scorePercent(),
                        r.passed()}).toList());
            }
            case "learners" -> {
                var rows = learners(filter, learnerStatus, exportPage(sort, "userId", LEARNER_SORTS)).getContent();
                yield Csv.write(List.of("Mã cán bộ", "Họ tên", "Đơn vị", "Nhóm", "Tình trạng", "Lượt đã dùng",
                    "Lượt còn lại", "Điểm cao nhất", "Điểm gần nhất", "Điểm cuối", "Đạt", "Nộp gần nhất"),
                    limit(rows).stream().map(r -> new Object[] {r.userId(), r.userName(), r.orgName(), r.teamName(),
                        r.learnerStatus(), r.attemptsUsed(), r.attemptsLeft(), r.bestScore(), r.lastScore(),
                        r.finalScore(), r.passed(), r.lastSubmittedAt()}).toList());
            }
            case "competency" -> {
                var rows = competency(filter, groupBy, exportPage(sort, "groupKey", COMPETENCY_SORTS)).getContent();
                yield Csv.write(List.of("Nhóm/đơn vị", "Tên", "Mã năng lực", "Năng lực", "Số câu", "Số câu đúng",
                    "Điểm tối đa", "Điểm đạt", "Tỷ lệ %"), limit(rows).stream().map(r -> new Object[] {r.groupKey(),
                        r.groupName(), r.code(), r.name(), r.totalQuestions(), r.correctCount(), r.maxScore(),
                        r.score(), r.percent()}).toList());
            }
            case "questions" -> {
                var rows = reports.questions(criteria(filter, null), exportPage(sort, "wrongPercent,desc",
                    QUESTION_SORTS)).getContent();
                yield Csv.write(List.of("ID câu hỏi", "Phiên bản", "Mã", "Nội dung", "Số lần phát", "Số lần đúng",
                    "Số lần bỏ trống", "% sai"), limit(rows).stream().map(r -> new Object[] {r.questionId(),
                        r.questionVersion(), r.code(), r.contentPreview(), r.timesServed(), r.timesCorrect(),
                        r.timesBlank(), r.wrongPercent()}).toList());
            }
            default -> throw BusinessException.validation("type", "INVALID_VALUE", "Giá trị không hợp lệ.");
        };
    }

    private Page<AssessmentViews.ReportResultRow> results(Filter filter, String userId, String status,
                                                          Boolean passed, Pageable page) {
        if (status != null && !ATTEMPT_STATUSES.contains(status)) {
            throw BusinessException.validation("status", "INVALID_VALUE", "Giá trị không hợp lệ.");
        }
        Page<AssessmentViews.ResultRowData> rows = reports.results(criteria(filter, blankToNull(userId)), status,
            passed, page);
        var users = learning.fullNames(rows.getContent().stream().map(AssessmentViews.ResultRowData::userId)
            .distinct().toList());
        var orgs = learning.orgNames(rows.getContent().stream().map(AssessmentViews.ResultRowData::orgId)
            .filter(Objects::nonNull).distinct().toList());
        var teams = learning.teamNames(rows.getContent().stream().map(AssessmentViews.ResultRowData::teamId)
            .filter(Objects::nonNull).distinct().toList());
        return rows.map(r -> new AssessmentViews.ReportResultRow(r.attemptId(), r.quizId(), r.quizTitle(),
            r.courseId(), r.userId(), users.get(r.userId()), r.orgId(), orgs.get(r.orgId()), r.teamId(),
            teams.get(r.teamId()), r.attemptNo(), r.status(), r.submitType(), r.startedAt(), r.submittedAt(),
            r.correctCount(), r.totalQuestions(), r.scorePercent(), r.passed()));
    }

    /** Học viên có lần học của khóa chứa đề, gồm cả người chưa thi; quizId bắt buộc. */
    private Page<AssessmentViews.ReportLearnerRow> learners(Filter filter, String learnerStatus, Pageable page) {
        if (filter.quizId() == null) {
            throw BusinessException.validation("quizId", "REQUIRED", "Thông tin này là bắt buộc.");
        }
        if (learnerStatus != null && !LEARNER_STATUSES.contains(learnerStatus)) {
            throw BusinessException.validation("learnerStatus", "INVALID_VALUE", "Giá trị không hợp lệ.");
        }
        Quiz quiz = repository.require(Quiz.class, filter.quizId());
        if (Boolean.TRUE.equals(quiz.getDeleted())) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
        Criteria criteria = criteria(filter, null);
        Page<AssessmentViews.LearnerRowData> rows = reports.learners(quiz.getId(), quiz.getCourseId(),
            quiz.getScoreMethod(), ProgressStatus.WITHDRAWN, criteria, learnerStatus, page);
        List<String> userIds = rows.getContent().stream().map(AssessmentViews.LearnerRowData::userId).toList();
        Map<String, Long> grants = reports.grants(quiz.getId(), userIds);
        var users = learning.fullNames(userIds);
        var orgs = learning.orgNames(rows.getContent().stream().map(AssessmentViews.LearnerRowData::orgId)
            .filter(Objects::nonNull).distinct().toList());
        var teams = learning.teamNames(rows.getContent().stream().map(AssessmentViews.LearnerRowData::teamId)
            .filter(Objects::nonNull).distinct().toList());
        return rows.map(r -> new AssessmentViews.ReportLearnerRow(r.userId(), users.get(r.userId()),
            r.enrollmentId(), r.orgId(), orgs.get(r.orgId()), r.teamId(), teams.get(r.teamId()), r.learnerStatus(),
            r.attemptsUsed(), Math.max(0, quiz.getMaxAttempts() + grants.getOrDefault(r.userId(), 0L)
            - r.attemptsUsed()), r.bestScore(), r.lastScore(), r.finalScore(), r.passed(), r.lastSubmittedAt()));
    }

    private Page<AssessmentViews.ReportCompetencyRow> competency(Filter filter, String groupBy, Pageable page) {
        boolean byUser = "user".equals(groupBy);
        if (!byUser && !"org".equals(groupBy)) {
            throw BusinessException.validation("groupBy", "INVALID_VALUE", "Chỉ hỗ trợ user hoặc org.");
        }
        Page<AssessmentViews.CompetencyRowData> rows = reports.competency(criteria(filter, null), byUser, page);
        List<String> keys = rows.getContent().stream().map(AssessmentViews.CompetencyRowData::groupKey)
            .filter(Objects::nonNull).distinct().toList();
        Map<String, String> names = byUser ? learning.fullNames(keys) : orgNamesByKey(keys);
        return rows.map(r -> new AssessmentViews.ReportCompetencyRow(r.groupKey(), names.get(r.groupKey()),
            r.competencyId(), r.code(), r.name(), r.totalQuestions(), r.correctCount(), r.maxScore(), r.score(),
            percent(r.score(), r.maxScore())));
    }

    private Map<String, String> orgNamesByKey(List<String> keys) {
        Map<Long, String> names = learning.orgNames(keys.stream().map(Long::valueOf).toList());
        return keys.stream().filter(key -> names.containsKey(Long.valueOf(key)))
            .collect(Collectors.toMap(key -> key, key -> names.get(Long.valueOf(key))));
    }

    /** Phạm vi của caller cộng bộ lọc; đơn vị lọc gồm cây con active, chỉ thu hẹp thêm trong phạm vi. */
    private Criteria criteria(Filter filter, String userId) {
        ReportScope scope = learning.reportScope();
        if (filter.from() != null && filter.to() != null && !filter.to().isAfter(filter.from())) {
            throw BusinessException.validation("to", "OUT_OF_RANGE", "Thời điểm kết thúc phải sau thời điểm bắt đầu.");
        }
        Set<Long> orgs = filter.orgId() == null ? null : learning.orgTree(filter.orgId());
        return new Criteria(scope.global(), scope.orgIds(), scope.teamIds(), orgs, filter.teamId(),
            filter.courseId(), filter.quizId(), userId, filter.from(), filter.to());
    }

    private static Pageable exportPage(String sort, String fallback, Set<String> allowed) {
        return PageRequest.of(0, AssessmentLimits.MAX_EXPORT_ROWS + 1,
            PageQuery.of(0, 1, sort == null ? fallback : sort, allowed).getSort());
    }

    private static <T> List<T> limit(List<T> rows) {
        if (rows.size() > AssessmentLimits.MAX_EXPORT_ROWS) {
            throw new BusinessException(AssessmentResponseCode.EXPORT_TOO_LARGE);
        }
        return rows;
    }

    private static AssessmentViews.ReportGroup group(AssessmentViews.ReportGroupData data, String name) {
        return new AssessmentViews.ReportGroup(data.id(), name, data.gradedAttempts(), data.learners(),
            data.passedLearners(), rate(data.passedLearners(), data.learners()), data.averageScore());
    }

    private static BigDecimal rate(Long part, Long total) {
        return total == null || total == 0 ? null
            : BigDecimal.valueOf(part).multiply(HUNDRED).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percent(BigDecimal score, BigDecimal max) {
        return max == null || max.signum() == 0 ? null
            : score.multiply(HUNDRED).divide(max, 2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
