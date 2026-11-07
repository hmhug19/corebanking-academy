package vn.com.coreacademy.learning.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.export.CsvWriter;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Person;
import vn.com.coreacademy.learning.constants.LearningCodes.LeaderboardScope;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.GamificationImpactDtos.Assigned;
import vn.com.coreacademy.learning.dto.GamificationImpactDtos.BadgeCount;
import vn.com.coreacademy.learning.dto.GamificationImpactDtos.Comparison;
import vn.com.coreacademy.learning.dto.GamificationImpactDtos.ImpactReport;
import vn.com.coreacademy.learning.dto.GamificationImpactDtos.LevelCount;
import vn.com.coreacademy.learning.dto.GamificationImpactDtos.OptOut;
import vn.com.coreacademy.learning.dto.GamificationImpactDtos.SourceXp;
import vn.com.coreacademy.learning.dto.GamificationImpactDtos.WeekPoint;
import vn.com.coreacademy.learning.dto.GamificationImpactDtos.Window;
import vn.com.coreacademy.learning.dto.LearningLevelViews.LevelStep;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.GamificationImpactRepository;
import vn.com.coreacademy.learning.repository.LearnerPreferenceRepository;
import vn.com.coreacademy.learning.repository.XpLedgerRepository;
import vn.com.coreacademy.learning.repository.XpLedgerRepository.ConfigRow;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess.ReportScope;

/**
 * Báo cáo tác động của gamification cho quản trị hệ thống, quản trị đơn vị và giảng viên. Phạm vi là học viên thuộc các
 * đơn vị/nhóm trong grant báo cáo của người gọi (server chốt, không tin tham số), thu hẹp thêm bằng orgId. Chỉ trả số
 * tổng hợp; CSV được dựng từ chính báo cáo đó nên khớp số trên màn hình. Mốc ra mắt là lúc lưu phiên bản cấu hình 1; so
 * sánh trước/sau lấy hai cửa sổ liền kề 28 ngày quanh mốc đó, không phụ thuộc kỳ đang chọn.
 */
@Service
@Transactional // không readOnly: ledger.currentConfig() có thể seed phiên bản cấu hình 1 ở lần đầu
@RequiredArgsConstructor
public class GamificationImpactService {
    static final int COMPARISON_DAYS = 28;
    static final int MAX_PERIOD_DAYS = 366;
    private static final ZoneId ZONE = ZoneId.of(LearningAnalyticsService.TIME_ZONE);

    private final LearningAssessmentAccess reportAccess;
    private final LearningIdentityService identity;
    private final GamificationImpactRepository repository;
    private final LearnerPreferenceRepository preferences;
    private final XpLedgerService ledger;
    private final XpLedgerRepository configs;
    private final LevelService levels;

    public ImpactReport report(LocalDate from, LocalDate to, Long orgId) {
        LocalDate today = LearningActivityService.localDate(Instant.now());
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(27) : from;
        if (start.isAfter(end)) {
            throw BusinessException.validation("to", "INVALID_VALUE", "Ngày kết thúc phải từ ngày bắt đầu trở đi.");
        }
        if (start.plusDays(MAX_PERIOD_DAYS).isBefore(end)) {
            throw BusinessException.validation("to", "INVALID_VALUE",
                "Kỳ báo cáo tối đa " + MAX_PERIOD_DAYS + " ngày.");
        }
        Set<String> ids = learnersInScope(orgId);
        Instant fromAt = start.atStartOfDay(ZONE).toInstant();
        Instant toAt = end.plusDays(1).atStartOfDay(ZONE).toInstant();

        Instant launch = launchedAt();
        List<WeekPoint> weeks = repository.weeklyActive(ids, fromAt, toAt).stream()
            .map(week -> new WeekPoint(week.weekStart(), week.learners())).toList();
        List<SourceXp> sources = repository.xpBySource(ids, fromAt, toAt).stream()
            .map(row -> new SourceXp(row.source(), row.xp(), row.learners())).toList();
        List<BadgeCount> badges = repository.badgesAwarded(ids, fromAt, toAt).stream()
            .map(row -> new BadgeCount(row.badgeId(), row.name(), row.awarded())).toList();
        int hidden = preferences.hiddenAmong(ids).size();
        return new ImpactReport(start, end, launch, ids.size(), weeks, assigned(ids, fromAt, toAt),
            comparison(ids, launch), sources, badges, levelCounts(ids),
            new OptOut(hidden, ids.size(), percent(hidden, ids.size())));
    }

    /** CSV từ chính báo cáo: mỗi dòng là một số trên màn hình, không có dòng theo người. */
    public byte[] export(LocalDate from, LocalDate to, Long orgId) {
        ImpactReport report = report(from, to, orgId);
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[] {"Phạm vi", "Từ ngày", report.from()});
        rows.add(new Object[] {"Phạm vi", "Đến ngày", report.to()});
        rows.add(new Object[] {"Phạm vi", "Số học viên", report.learners()});
        report.weeklyActive().forEach(week -> rows.add(new Object[] {"Học viên hoạt động theo tuần",
            "Tuần từ " + week.weekStart(), week.learners()}));
        addAssigned(rows, "Khóa được giao", "", report.assigned());
        Comparison comparison = report.comparison();
        if (comparison != null) {
            rows.add(new Object[] {"So sánh trước và sau ra mắt", "Ngày ra mắt",
                comparison.launchedAt().atZone(ZONE).toLocalDate()});
            addWindow(rows, "Trước ra mắt", comparison.before());
            if (comparison.after() != null) {
                addWindow(rows, "Sau ra mắt", comparison.after());
            }
        }
        report.xpBySource().forEach(source -> {
            rows.add(new Object[] {"XP theo nguồn", sourceLabel(source.source()) + " - XP", source.xp()});
            rows.add(new Object[] {"XP theo nguồn", sourceLabel(source.source()) + " - số học viên",
                source.learners()});
        });
        report.badges().forEach(badge -> rows.add(new Object[] {"Huy hiệu được trao", badge.name(),
            badge.awarded()}));
        report.levels().forEach(level -> rows.add(new Object[] {"Học viên theo cấp",
            "Cấp " + level.level() + " - " + level.name(), level.learners()}));
        rows.add(new Object[] {"Tắt hiển thị tên", "Số học viên tắt", report.optOut().hidden()});
        rows.add(new Object[] {"Tắt hiển thị tên", "Tỷ lệ (%)", report.optOut().ratePercent()});
        return CsvWriter.write(List.of("Nhóm", "Chỉ số", "Giá trị"), rows);
    }

    static String sourceLabel(String source) {
        return switch (source) {
            case "LESSON" -> "Hoàn thành bài học";
            case "QUIZ_PASS" -> "Đạt bài kiểm tra";
            case "QUIZ_FIRST_TRY_BONUS" -> "Thưởng đạt ngay lần đầu";
            case "COURSE" -> "Hoàn thành khóa học";
            case "COMMUNITY" -> "Đóng góp cộng đồng";
            case "ADJUSTMENT" -> "Điều chỉnh của quản trị";
            default -> source;
        };
    }

    private static void addAssigned(List<Object[]> rows, String group, String prefix, Assigned assigned) {
        rows.add(new Object[] {group, prefix + "Số khóa được giao", assigned.assigned()});
        rows.add(new Object[] {group, prefix + "Đã hoàn thành", assigned.completed()});
        rows.add(new Object[] {group, prefix + "Tỷ lệ hoàn thành (%)", assigned.completionRatePercent()});
        rows.add(new Object[] {group, prefix + "Hoàn thành đúng hạn", assigned.completedOnTime()});
        rows.add(new Object[] {group, prefix + "Tỷ lệ đúng hạn (%)", assigned.onTimeRatePercent()});
    }

    private static void addWindow(List<Object[]> rows, String label, Window window) {
        String group = "So sánh trước và sau ra mắt";
        rows.add(new Object[] {group, label + ": từ ngày", window.from()});
        rows.add(new Object[] {group, label + ": đến ngày", window.to()});
        rows.add(new Object[] {group, label + ": học viên hoạt động trung bình mỗi tuần",
            window.weeklyActiveLearners()});
        addAssigned(rows, group, label + ": ", window.assigned());
    }

    private Instant launchedAt() {
        ledger.currentConfig();
        return configs.configVersions().stream().filter(row -> row.config().versionNo() == 1)
            .map(ConfigRow::createdAt).findFirst().orElse(null);
    }

    /** Hai cửa sổ liền kề quanh mốc ra mắt; cửa sổ sau chỉ tính đến hiện tại, bỏ nếu chưa qua ngày nào. */
    private Comparison comparison(Set<String> ids, Instant launch) {
        if (launch == null) {
            return null;
        }
        Instant now = Instant.now();
        Duration window = Duration.ofDays(COMPARISON_DAYS);
        Window before = window(ids, launch.minus(window), launch);
        Instant afterEnd = launch.plus(window).isAfter(now) ? now : launch.plus(window);
        Window after = afterEnd.isAfter(launch) ? window(ids, launch, afterEnd) : null;
        return new Comparison(launch, COMPARISON_DAYS, before, after);
    }

    private Window window(Set<String> ids, Instant from, Instant to) {
        double weeks = Duration.between(from, to).toHours() / (24.0 * 7);
        int learnerWeeks = repository.activeLearnerWeeks(ids, from, to);
        Double weekly = weeks <= 0 ? null : Math.round(learnerWeeks / weeks * 10.0) / 10.0;
        return new Window(from.atZone(ZONE).toLocalDate(), to.minusMillis(1).atZone(ZONE).toLocalDate(), weekly,
            assigned(ids, from, to));
    }

    private Assigned assigned(Set<String> ids, Instant from, Instant to) {
        var row = repository.assignments(ids, from, to);
        return new Assigned(row.assigned(), row.completed(), row.onTime(), percent(row.completed(), row.assigned()),
            percent(row.onTime(), row.assigned()));
    }

    private List<LevelCount> levelCounts(Set<String> ids) {
        List<LevelStep> ladder = levels.ladderOf(ledger.currentConfig());
        Map<Integer, Integer> stored = repository.learnersPerLevel(ids);
        int withoutRow = ids.size() - stored.values().stream().mapToInt(Integer::intValue).sum();
        List<LevelCount> counts = new ArrayList<>();
        for (LevelStep step : ladder) {
            int learners = stored.getOrDefault(step.level(), 0) + (step.level() == 1 ? Math.max(0, withoutRow) : 0);
            counts.add(new LevelCount(step.level(), step.name(), learners));
        }
        return counts;
    }

    /** Học viên trong phạm vi báo cáo của người gọi, thu hẹp bằng orgId (gồm cây con) nếu có. */
    private Set<String> learnersInScope(Long orgId) {
        ReportScope scope = reportAccess.reportScope();
        Set<Long> filter = orgId == null ? null : reportAccess.orgTree(orgId);
        List<Person> learners = identity.rankableLearners(LeaderboardScope.ALL, Set.of(), null,
            LearningLimits.MAX_LEADERBOARD_LEARNERS);
        if (learners.size() > LearningLimits.MAX_LEADERBOARD_LEARNERS) {
            throw new BusinessException(LearningResponseCode.LEADERBOARD_TOO_LARGE);
        }
        Set<String> ids = new HashSet<>();
        for (Person person : learners) {
            boolean inScope = scope.global()
                || (person.orgId() != null && scope.orgIds().contains(person.orgId()))
                || (person.teamId() != null && scope.teamIds().contains(person.teamId()));
            boolean inFilter = filter == null || (person.orgId() != null && filter.contains(person.orgId()));
            if (inScope && inFilter) {
                ids.add(person.id());
            }
        }
        return ids;
    }

    /** Tỷ lệ phần trăm làm tròn một chữ số thập phân; null khi mẫu số bằng 0. */
    static Double percent(int part, int whole) {
        return whole == 0 ? null : Math.round(part * 1000.0 / whole) / 10.0;
    }
}
