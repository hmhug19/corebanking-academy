package vn.com.coreacademy.learning.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.export.CsvWriter;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningAnalytics;
import vn.com.coreacademy.learning.dto.LearningAnalytics.Home;
import vn.com.coreacademy.learning.dto.LearningReportFilter;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.custom.LearningAnalyticsRepository;
import vn.com.coreacademy.learning.repository.custom.LearningAnalyticsRepository.Criteria;
import vn.com.coreacademy.learning.repository.custom.LearningAnalyticsRepository.Manager;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess.ReportScope;

/** Báo cáo hiện trạng: scope theo đơn vị/nhóm chụp lúc ghi danh, không gán lại lịch sử theo hồ sơ hiện tại. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningAnalyticsService {
    public static final String TIME_ZONE = "Asia/Ho_Chi_Minh";

    private final LearningAnalyticsRepository repository;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningAssessmentAccess reportAccess;

    public Home home() {
        Criteria criteria = learnerCriteria();
        return new Home(repository.counts(criteria), repository.next(criteria),
            repository.deadlines(criteria, LearningLimits.DASHBOARD_DEADLINES));
    }

    public PageResponse<LearningAnalytics.Group> myPrograms(int page, int size) {
        return PageResponse.from(repository.groups(learnerCriteria(), "program",
            PageQuery.of(page, size, "id", Set.of("id"))));
    }

    public LearningAnalytics.Dashboard dashboard() {
        var actor = access.actor();
        access.requireStaff(actor);
        var person = actor.person();
        var criteria = new Criteria(LearningReportFilter.empty(), null, Set.of(), null, null, Set.of(),
            new Manager(access.searchScope(actor), person.id(), person.orgId()));
        Instant now = Instant.now();
        ZoneId zone = ZoneId.of(TIME_ZONE);
        LocalDate today = now.atZone(zone).toLocalDate();
        int days = 14;
        LocalDate first = today.minusDays(days - 1);
        return new LearningAnalytics.Dashboard(now, TIME_ZONE, repository.counts(criteria),
            repository.daily(criteria, first, first.atStartOfDay(zone).toInstant(),
                today.plusDays(1).atStartOfDay(zone).toInstant(), TIME_ZONE, days));
    }

    public LearningAnalytics.Summary summary(LearningReportFilter filter) {
        return new LearningAnalytics.Summary(Instant.now(), TIME_ZONE, repository.counts(reportCriteria(filter)));
    }

    public PageResponse<LearningAnalytics.EnrollmentRow> enrollments(LearningReportFilter filter, int page, int size,
                                                                    String sort) {
        return PageResponse.from(withNames(repository.enrollments(reportCriteria(filter),
            PageQuery.of(page, size, sort, LearningAnalyticsRepository.SORTS))));
    }

    public PageResponse<LearningAnalytics.Group> groups(LearningReportFilter filter, String groupBy, int page,
                                                        int size) {
        if (!LearningAnalyticsRepository.GROUP_TYPES.contains(groupBy)) {
            throw BusinessException.validation("groupBy", "INVALID_VALUE",
                "Chọn user, course, program, org hoặc team.");
        }
        Page<LearningAnalytics.Group> rows = repository.groups(reportCriteria(filter), groupBy,
            PageQuery.of(page, size, "id", Set.of("id")));
        Map<String, String> names = groupNames(rows, groupBy);
        return PageResponse.from(rows.map(r -> new LearningAnalytics.Group(r.id(),
            r.id() == null ? null : names.getOrDefault(r.id(), r.name()), r.enrollments(), r.learners(), r.courses(),
            r.completed(), r.inProgress(), r.notStarted(), r.overdue(), r.averageProgress(), r.completionRate())));
    }

    /** Cùng query và scope với danh sách; đọc tối đa giới hạn + 1 để từ chối, không cắt im lặng. */
    public byte[] export(LearningReportFilter filter, String sort) {
        Criteria criteria = reportCriteria(filter);
        var order = PageQuery.of(0, 1, sort, LearningAnalyticsRepository.SORTS).getSort();
        Page<LearningAnalytics.Enrollment> rows = repository.enrollments(criteria,
            PageRequest.of(0, LearningLimits.MAX_EXPORT_ROWS + 1, order));
        if (rows.getTotalElements() > LearningLimits.MAX_EXPORT_ROWS) {
            throw new BusinessException(LearningResponseCode.EXPORT_TOO_LARGE);
        }
        return CsvWriter.write(List.of("Mã cán bộ", "Họ tên", "Đơn vị", "Nhóm", "Mã khóa", "Khóa học", "Chương trình",
            "Trạng thái", "Tiến độ %", "Được giao", "Bắt buộc", "Ghi danh", "Bắt đầu", "Truy cập gần nhất",
            "Hoàn thành", "Hạn hoàn thành", "Quá hạn"), withNames(rows).stream().map(row -> {
                var r = row.enrollment();
                return new Object[] {r.userId(), row.fullName(), row.orgName(), row.teamName(), r.courseCode(),
                    r.courseTitle(), r.programName(), r.status(), r.progressPercent(), r.assigned(), r.mandatory(),
                    r.enrolledAt(), r.startedAt(), r.lastAccessAt(), r.completedAt(), r.dueAt(), r.overdue()};
            }).toList());
    }

    private Map<String, String> groupNames(Page<LearningAnalytics.Group> rows, String groupBy) {
        List<String> keys = rows.stream().map(LearningAnalytics.Group::id).filter(Objects::nonNull).toList();
        if ("user".equals(groupBy)) {
            return identity.fullNames(keys);
        }
        if ("org".equals(groupBy) || "team".equals(groupBy)) {
            List<Long> ids = keys.stream().map(Long::valueOf).toList();
            Map<Long, String> values = "org".equals(groupBy) ? reportAccess.orgNames(ids) : reportAccess.teamNames(ids);
            Map<String, String> names = new HashMap<>();
            values.forEach((id, name) -> names.put(id.toString(), name));
            return names;
        }
        return Map.of();
    }

    private Criteria learnerCriteria() {
        var actor = access.actor();
        access.requireLearner(actor);
        return new Criteria(LearningReportFilter.empty(), new ReportScope(false, Set.of(), Set.of()), Set.of(),
            actor.person().id(), actor.person().orgId(), identity.activeAncestorIds(actor.person().orgId()), null);
    }

    private Criteria reportCriteria(LearningReportFilter filter) {
        ReportScope scope = reportAccess.reportScope();
        if (filter.status() != null && !ProgressStatus.SEARCHABLE_VALUES.contains(filter.status())) {
            throw BusinessException.validation("status", "INVALID_VALUE", "Trạng thái học tập chưa hợp lệ.");
        }
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            throw BusinessException.validation("to", "INVALID_VALUE", "Thời điểm kết thúc phải sau thời điểm bắt đầu.");
        }
        Set<Long> orgs = filter.orgId() == null ? Set.of() : reportAccess.orgTree(filter.orgId());
        return new Criteria(filter, scope, orgs, null, null, Set.of(), null);
    }

    private Page<LearningAnalytics.EnrollmentRow> withNames(Page<LearningAnalytics.Enrollment> rows) {
        var users = identity.fullNames(rows.stream().map(LearningAnalytics.Enrollment::userId).distinct().toList());
        var orgs = reportAccess.orgNames(rows.stream().map(LearningAnalytics.Enrollment::orgId)
            .filter(Objects::nonNull).distinct().toList());
        var teams = reportAccess.teamNames(rows.stream().map(LearningAnalytics.Enrollment::teamId)
            .filter(Objects::nonNull).distinct().toList());
        return rows.map(r -> new LearningAnalytics.EnrollmentRow(r, users.get(r.userId()),
            r.orgId() == null ? null : orgs.get(r.orgId()), r.teamId() == null ? null : teams.get(r.teamId())));
    }
}
