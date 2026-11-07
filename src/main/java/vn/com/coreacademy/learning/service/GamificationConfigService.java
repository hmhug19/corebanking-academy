package vn.com.coreacademy.learning.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.constants.LearningCodes.CalendarDayType;
import vn.com.coreacademy.learning.dto.GamificationConfig;
import vn.com.coreacademy.learning.dto.GamificationConfigDtos.CalendarDayInput;
import vn.com.coreacademy.learning.dto.GamificationConfigDtos.CalendarDayView;
import vn.com.coreacademy.learning.dto.GamificationConfigDtos.ConfigVersionView;
import vn.com.coreacademy.learning.dto.GamificationConfigDtos.LevelInput;
import vn.com.coreacademy.learning.dto.GamificationConfigDtos.SaveCalendarYear;
import vn.com.coreacademy.learning.dto.GamificationConfigDtos.SaveConfig;
import vn.com.coreacademy.learning.dto.LearningLevelViews.LevelStep;
import vn.com.coreacademy.learning.repository.LearningStreakRepository;
import vn.com.coreacademy.learning.repository.LearningStreakRepository.CalendarEntry;
import vn.com.coreacademy.learning.repository.LevelRepository;
import vn.com.coreacademy.learning.repository.XpLedgerRepository;
import vn.com.coreacademy.learning.repository.XpLedgerRepository.ConfigRow;

/**
 * Cấu hình gamification của quản trị hệ thống: trọng số XP, ngưỡng thưởng, trần cộng đồng, bậc thang cấp độ và lịch
 * ngày làm việc. Mỗi lần lưu cấu hình thêm một phiên bản bất biến áp dụng ngay từ lúc lưu, nên XP đã ghi giữ nguyên số
 * cũ. Trước khi áp dụng bậc thang mới, cấp của mọi học viên được cập nhật theo bậc thang cũ để cấp đã đạt không mất.
 * Mọi thay đổi ghi audit cùng giao dịch.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class GamificationConfigService {
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;

    private final LearningAccessPolicy access;
    private final XpLedgerService ledger;
    private final XpLedgerRepository configs;
    private final LevelService levels;
    private final LevelRepository levelRepository;
    private final LearningStreakRepository calendar;
    private final IdentityAuditService audit;

    /** Phiên bản đang áp dụng. */
    public ConfigVersionView current() {
        requireSystemAdmin();
        ledger.currentConfig();
        return versions().get(0);
    }

    /** Mọi phiên bản, mới nhất trước. */
    public List<ConfigVersionView> versions() {
        requireSystemAdmin();
        ledger.currentConfig();
        List<ConfigRow> rows = configs.configVersions();
        List<ConfigVersionView> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ConfigRow row = rows.get(i);
            result.add(view(row.config(), row.createdBy(), row.createdAt(), i == 0));
        }
        return result;
    }

    public ConfigVersionView save(SaveConfig request) {
        Actor actor = requireSystemAdmin();
        List<LevelStep> ladder = ladderOf(request.levels());
        ledger.currentConfig();
        levels.refreshAll();
        GamificationConfig saved = configs.insertConfig(request.lessonXp(), request.quizPassXp(),
            request.firstTryBonusXp(), request.firstTryThresholdPercent(), request.communityXp(),
            request.communityDailyCap(), actor.person().id());
        levelRepository.seedLadder(saved.versionId(), ladder);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("versionNo", saved.versionNo());
        changes.put("lessonXp", saved.lessonXp());
        changes.put("quizPassXp", saved.quizPassXp());
        changes.put("firstTryBonusXp", saved.firstTryBonusXp());
        changes.put("firstTryThresholdPercent", saved.firstTryThresholdPercent());
        changes.put("communityXp", saved.communityXp());
        changes.put("communityDailyCap", saved.communityDailyCap());
        changes.put("levels", ladder.stream().map(step -> step.name() + ":" + step.minXp()).toList());
        audit.record(actor.person().id(), "GAMIFICATION_CONFIG_SAVED", "gamification_config",
            String.valueOf(saved.versionNo()), null, changes);
        return view(saved, actor.person().id(), null, true);
    }

    /** Ngày ngoại lệ của một năm, ngày tăng dần. */
    public List<CalendarDayView> calendar(int year) {
        requireSystemAdmin();
        requireYear(year);
        return calendar.calendarYear(year).stream()
            .map(row -> new CalendarDayView(((java.sql.Date) row[0]).toLocalDate(), (String) row[1], (String) row[2]))
            .toList();
    }

    /** Thay toàn bộ ngày ngoại lệ của một năm; chuỗi ngày học tính theo lịch mới từ lần đánh giá kế tiếp. */
    public List<CalendarDayView> saveCalendar(int year, SaveCalendarYear request) {
        Actor actor = requireSystemAdmin();
        requireYear(year);
        List<CalendarEntry> entries = new ArrayList<>();
        Set<LocalDate> seen = new HashSet<>();
        for (int i = 0; i < request.days().size(); i++) {
            CalendarDayInput day = request.days().get(i);
            String field = "days[" + i + "]";
            if (day.date().getYear() != year) {
                throw BusinessException.validation(field + ".date", "INVALID_VALUE",
                    "Ngày phải thuộc năm " + year + ".");
            }
            if (!CalendarDayType.ALL.contains(day.type())) {
                throw BusinessException.validation(field + ".type", "INVALID_VALUE",
                    "Loại ngày phải là ngày nghỉ hoặc ngày làm bù.");
            }
            if (!seen.add(day.date())) {
                throw BusinessException.validation(field + ".date", "DUPLICATE", "Ngày này đã có trong lịch.");
            }
            String note = day.note() == null || day.note().isBlank() ? null : day.note().strip();
            entries.add(new CalendarEntry(day.date(), day.type(), note));
        }
        entries.sort((a, b) -> a.date().compareTo(b.date()));
        calendar.replaceCalendarYear(year, entries, actor.person().id());
        audit.record(actor.person().id(), "WORKING_CALENDAR_SAVED", "working_calendar", String.valueOf(year), null,
            Map.of("year", year, "holidays", entries.stream().filter(e -> CalendarDayType.HOLIDAY.equals(e.type()))
                .count(), "makeupWorkdays", entries.stream()
                .filter(e -> CalendarDayType.MAKEUP_WORKDAY.equals(e.type())).count()));
        return calendar(year);
    }

    private ConfigVersionView view(GamificationConfig config, String author, java.time.Instant at, boolean current) {
        return new ConfigVersionView(config.versionNo(), config.lessonXp(), config.quizPassXp(),
            config.firstTryBonusXp(), config.firstTryThresholdPercent(), config.communityXp(),
            config.communityDailyCap(), levels.ladderOf(config), author, at, current);
    }

    /** Bậc đầu bằng 0, các bậc tăng dần ngặt, tên không rỗng; số cấp theo thứ tự từ 1. */
    static List<LevelStep> ladderOf(List<LevelInput> inputs) {
        List<LevelStep> ladder = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            LevelInput input = inputs.get(i);
            String name = input.name() == null ? "" : input.name().strip();
            if (name.isEmpty()) {
                throw BusinessException.validation("levels[" + i + "].name", "REQUIRED", "Cần nhập tên cấp.");
            }
            int minXp = input.minXp();
            if (i == 0 && minXp != 0) {
                throw BusinessException.validation("levels[0].minXp", "INVALID_VALUE", "Cấp đầu tiên phải từ 0 XP.");
            }
            if (i > 0 && minXp <= ladder.get(i - 1).minXp()) {
                throw BusinessException.validation("levels[" + i + "].minXp", "INVALID_VALUE",
                    "Ngưỡng XP phải lớn hơn cấp trước.");
            }
            ladder.add(new LevelStep(i + 1, name, minXp));
        }
        return ladder;
    }

    private static void requireYear(int year) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw BusinessException.validation("year", "INVALID_VALUE",
                "Năm phải từ " + MIN_YEAR + " đến " + MAX_YEAR + ".");
        }
    }

    private Actor requireSystemAdmin() {
        Actor actor = access.actor();
        if (!XpAdjustmentService.isSystemAdmin(actor)) {
            throw new AccessDeniedException("System admin required");
        }
        return actor;
    }
}
