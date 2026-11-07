package vn.com.coreacademy.learning.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.learning.constants.LearningCodes.BadgeRule;
import vn.com.coreacademy.learning.constants.LearningCodes.BadgeSource;
import vn.com.coreacademy.learning.constants.LearningCodes.BadgeStatus;
import vn.com.coreacademy.learning.dto.LearningBadgeViews.BadgeProgress;
import vn.com.coreacademy.learning.dto.LearningBadgeViews.BadgeView;
import vn.com.coreacademy.learning.dto.LearningBadgeViews.MyBadges;
import vn.com.coreacademy.learning.repository.BadgeRepository;
import vn.com.coreacademy.learning.repository.BadgeRepository.Definition;
import vn.com.coreacademy.learning.repository.LearningStreakRepository;

/**
 * Huy hiệu. Mỗi luật được kiểm tra khi có sự kiện có thể làm nó đúng (cùng sự kiện với XP) trong giao dịch của sự
 * kiện đó. Tạo huy hiệu mới thì trao bù âm thầm cho người đã đủ điều kiện (nguồn BACKFILL, không thông báo). Huy
 * hiệu đã trao không bao giờ bị thu hồi; huy hiệu ngừng dùng thôi không được trao nữa. Mỗi (người, huy hiệu) một dòng
 * nên chạy lại không trao hai lần. Luật HOURS_LEARNED được dành riêng và chưa xây.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BadgeService {
    private final BadgeRepository repository;
    private final LearningStreakRepository streaks;
    private final LearningAccessPolicy access;

    /** Huy hiệu của caller; chỉ học viên (LEARNER, OWN). Lần đầu tiên tạo bộ huy hiệu khởi đầu. */
    public MyBadges mine() {
        var actor = access.actor();
        access.requireLearner(actor);
        String userId = actor.person().id();
        ensureStarterSet();

        List<Definition> definitions = repository.definitions();
        Map<Long, Instant> earned = repository.earned(userId);
        Map<Long, String> programs = repository.programNames(definitions.stream()
            .map(Definition::paramProgramId).filter(id -> id != null).distinct().toList());

        List<BadgeView> have = new ArrayList<>();
        List<BadgeView> missing = new ArrayList<>();
        for (Definition definition : definitions) {
            Instant at = earned.get(definition.badgeId());
            boolean retired = BadgeStatus.RETIRED.equals(definition.status());
            if (at != null) {
                have.add(view(definition, programs, at, null));
            } else if (!retired) {
                BadgeProgress progress = null;
                if (BadgeRule.PROGRAM_COMPLETED.equals(definition.ruleType())) {
                    int[] done = repository.programProgress(definition.paramProgramId(), userId);
                    progress = new BadgeProgress(done[0], done[1]);
                }
                missing.add(view(definition, programs, null, progress));
            }
        }
        have.sort(Comparator.comparing(BadgeView::earnedAt).reversed());
        return new MyBadges(have, missing);
    }

    /**
     * Kiểm tra các luật thuộc ruleTypes cho một người sau sự kiện của họ và trao huy hiệu đã đủ điều kiện. Trả số
     * huy hiệu vừa trao.
     */
    public int evaluate(String userId, Collection<String> ruleTypes) {
        repository.flush();
        List<Definition> candidates = repository.activeNotHeld(userId, ruleTypes);
        if (candidates.isEmpty()) {
            return 0;
        }
        Metrics metrics = new Metrics(userId);
        int awarded = 0;
        for (Definition definition : candidates) {
            if (metrics.qualifiers(definition).contains(userId)
                && repository.award(userId, definition.badgeId(), BadgeSource.EVENT)) {
                awarded++;
            }
        }
        return awarded;
    }

    /**
     * Trao bù một huy hiệu cho mọi người đã đủ điều kiện, không thông báo. Chạy khi huy hiệu được tạo; huy hiệu ngừng
     * dùng không được trao. Trả số người mới nhận.
     */
    public int backfill(long badgeId) {
        repository.flush();
        Definition definition = repository.definition(badgeId).orElseThrow();
        if (!BadgeStatus.ACTIVE.equals(definition.status())) {
            return 0;
        }
        return repository.awardAll(badgeId, new Metrics(null).qualifiers(definition), BadgeSource.BACKFILL);
    }

    /** Bộ huy hiệu khởi đầu, tạo một lần khi chưa có huy hiệu nào; người đã đủ điều kiện nhận ngay, âm thầm. */
    void ensureStarterSet() {
        if (repository.countDefinitions() > 0) {
            return;
        }
        List<Long> created = new ArrayList<>();
        int sort = 0;
        sort = seed(created, "FIRST_COURSE", "Khóa học đầu tiên", "Hoàn thành khóa học đầu tiên của bạn.",
            "graduation-cap", BadgeRule.COURSES_COMPLETED, 1, null, sort);
        sort = seed(created, "COURSES_10", "Mười khóa học", "Hoàn thành 10 khóa học.", "library",
            BadgeRule.COURSES_COMPLETED, 10, null, sort);
        for (Object[] program : repository.activePrograms()) {
            String name = (String) program[2];
            sort = seed(created, "PROGRAM_" + program[1], "Thành thạo " + name,
                "Hoàn thành mọi khóa học của chương trình " + name + ".", "route", BadgeRule.PROGRAM_COMPLETED, null,
                ((Number) program[0]).longValue(), sort);
        }
        sort = seed(created, "FIRST_TRY_PERFECT_3", "Chuẩn ngay lần đầu",
            "Đạt 100% ngay lần làm đầu tiên ở 3 bài kiểm tra.", "target", BadgeRule.FIRST_TRY_PERFECT, 3, null, sort);
        sort = seed(created, "BEFORE_DEADLINE_3", "Đúng hẹn", "Hoàn thành 3 khóa học được giao trước hạn.",
            "alarm-clock-check", BadgeRule.ASSIGNED_BEFORE_DEADLINE, 3, null, sort);
        for (int days : new int[] {5, 20, 60}) {
            sort = seed(created, "STREAK_" + days, "Đều đặn " + days + " ngày",
                "Duy trì chuỗi " + days + " ngày làm việc liên tiếp.", "flame", BadgeRule.STREAK_WORKING_DAYS, days,
                null, sort);
        }
        created.forEach(this::backfill);
    }

    private int seed(List<Long> created, String code, String name, String description, String icon, String rule,
                     Integer n, Long programId, int sort) {
        repository.insertDefinition(code, name, description, icon, rule, n, programId, sort, null)
            .ifPresent(created::add);
        return sort + 1;
    }

    private static BadgeView view(Definition definition, Map<Long, String> programs, Instant earnedAt,
                                  BadgeProgress progress) {
        return new BadgeView(definition.badgeId(), definition.name(), definition.description(), definition.icon(),
            condition(definition,
                definition.paramProgramId() == null ? null : programs.get(definition.paramProgramId())), earnedAt,
            BadgeStatus.RETIRED.equals(definition.status()), progress);
    }

    /** Điều kiện nhận của một luật, bằng tiếng Việt. */
    static String condition(Definition definition, String programName) {
        int n = definition.paramN() == null ? 0 : definition.paramN();
        return switch (definition.ruleType()) {
            case BadgeRule.COURSES_COMPLETED -> n == 1 ? "Hoàn thành khóa học đầu tiên" : "Hoàn thành " + n + " khóa học";
            case BadgeRule.PROGRAM_COMPLETED -> "Hoàn thành mọi khóa học của chương trình "
                + (programName == null ? "" : programName);
            case BadgeRule.FIRST_TRY_PERFECT -> "Đạt 100% ngay lần làm đầu tiên ở " + n + " bài kiểm tra";
            case BadgeRule.ASSIGNED_BEFORE_DEADLINE -> "Hoàn thành " + n + " khóa học được giao trước hạn";
            case BadgeRule.STREAK_WORKING_DAYS -> "Duy trì chuỗi " + n + " ngày làm việc liên tiếp";
            default -> definition.description();
        };
    }

    /** Số đo của một người (hoặc mọi người khi userId null), mỗi loại chỉ tính một lần trong một lần gọi. */
    private final class Metrics {
        private final String userId;
        private final Map<String, Map<String, Integer>> counts = new HashMap<>();

        Metrics(String userId) {
            this.userId = userId;
        }

        /** Những người đang đủ điều kiện của huy hiệu. */
        Set<String> qualifiers(Definition definition) {
            if (BadgeRule.PROGRAM_COMPLETED.equals(definition.ruleType())) {
                return repository.programCompleters(definition.paramProgramId(), userId);
            }
            int n = definition.paramN();
            Set<String> result = new HashSet<>();
            counts(definition.ruleType()).forEach((user, count) -> {
                if (count >= n) {
                    result.add(user);
                }
            });
            return result;
        }

        private Map<String, Integer> counts(String rule) {
            return counts.computeIfAbsent(rule, key -> switch (key) {
                case BadgeRule.COURSES_COMPLETED -> repository.completedCourses(userId);
                case BadgeRule.FIRST_TRY_PERFECT -> repository.firstTryPerfect(userId);
                case BadgeRule.ASSIGNED_BEFORE_DEADLINE -> repository.beforeDeadline(userId);
                case BadgeRule.STREAK_WORKING_DAYS -> longestStreaks();
                default -> Map.of();
            });
        }

        private Map<String, Integer> longestStreaks() {
            List<String> ids = userId == null ? repository.learners() : List.of(userId);
            Map<String, Set<LocalDate>> days = streaks.qualifyingDays(ids);
            Map<LocalDate, String> calendar = streaks.calendar();
            LocalDate today = LearningActivityService.localDate(Instant.now());
            Map<String, Integer> result = new HashMap<>();
            days.forEach((user, active) -> result.put(user, WorkingDayStreak.compute(active, calendar, today)
                .longest()));
            return result;
        }
    }
}
