package vn.com.coreacademy.learning.service;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Person;
import vn.com.coreacademy.learning.constants.LearningCodes.LeaderboardScope;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningPointsViews.UnitLeaderboard;
import vn.com.coreacademy.learning.dto.LearningPointsViews.UnitRow;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.LearningPointsRepository;
import vn.com.coreacademy.learning.repository.LearningStreakRepository;

/**
 * Bảng xếp hạng đơn vị: XP trung bình mỗi học viên đang hoạt động trong kỳ (học viên có ít nhất một dòng sổ XP trong kỳ) và
 * tỷ lệ tham gia (học viên có ít nhất một ngày đủ điều kiện trong kỳ trên tổng học viên của đơn vị). Chỉ đơn vị từ 5
 * học viên trở lên được xếp hạng; trả 10 đơn vị đầu và đơn vị của người gọi, không bao giờ trả các hạng cuối. Người
 * tắt hiển thị tên trên bảng vẫn được tính vào đơn vị.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningUnitLeaderboardService {
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningPointsRepository points;
    private final LearningStreakRepository streaks;

    private record Unit(long orgId, int learners, double averageXp, int participationPercent) {
    }

    public UnitLeaderboard units(String period, int offset) {
        var actor = access.actor();
        access.requireLearner(actor);
        Person caller = actor.person();
        LocalDate today = LearningActivityService.localDate(Instant.now());
        LeaderboardRange range = LeaderboardRange.of(period, offset, today);

        List<Person> learners = identity.rankableLearners(LeaderboardScope.ALL, Set.of(), null,
            LearningLimits.MAX_LEADERBOARD_LEARNERS);
        if (learners.size() > LearningLimits.MAX_LEADERBOARD_LEARNERS) {
            throw new BusinessException(LearningResponseCode.LEADERBOARD_TOO_LARGE);
        }
        Map<Long, List<Person>> byUnit = new HashMap<>();
        for (Person person : learners) {
            if (person.orgId() != null) {
                byUnit.computeIfAbsent(person.orgId(), key -> new ArrayList<>()).add(person);
            }
        }
        boolean callerTooSmall = caller.orgId() != null
            && byUnit.getOrDefault(caller.orgId(), List.of()).size() < LearningLimits.UNIT_LEADERBOARD_MIN_LEARNERS;
        byUnit.values().removeIf(members -> members.size() < LearningLimits.UNIT_LEADERBOARD_MIN_LEARNERS);

        Set<String> ids = new HashSet<>();
        byUnit.values().forEach(members -> members.forEach(person -> ids.add(person.id())));
        Map<String, Long> sums = points.sumsBetween(ids, range.fromInstant(), range.endInstant());
        Map<String, Set<LocalDate>> days = streaks.qualifyingDays(ids);

        List<Unit> units = new ArrayList<>();
        byUnit.forEach((orgId, members) -> units.add(unit(orgId, members, sums, days, range)));
        Map<Long, String> names = identity.orgNames(units.stream().map(Unit::orgId).toList());
        Collator collator = Collator.getInstance(Locale.forLanguageTag("vi"));
        units.sort(Comparator.<Unit>comparingDouble(u -> -u.averageXp())
            .thenComparing(u -> names.getOrDefault(u.orgId(), ""), collator).thenComparingLong(Unit::orgId));
        int[] ranks = new int[units.size()];
        for (int i = 0; i < units.size(); i++) {
            boolean tied = i > 0 && units.get(i).averageXp() == units.get(i - 1).averageXp();
            ranks[i] = tied ? ranks[i - 1] : i + 1;
        }

        List<UnitRow> top = new ArrayList<>();
        UnitRow mine = null;
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            boolean isMine = caller.orgId() != null && unit.orgId() == caller.orgId();
            UnitRow row = new UnitRow(ranks[i], unit.orgId(), names.getOrDefault(unit.orgId(), ""),
                unit.averageXp(), unit.participationPercent(), isMine);
            if (i < LearningLimits.LEADERBOARD_TOP) {
                top.add(row);
            }
            if (isMine) {
                mine = row;
            }
        }
        return new UnitLeaderboard(period, range.offset(), range.from(), range.to(), top, mine, callerTooSmall);
    }

    private static Unit unit(long orgId, List<Person> members, Map<String, Long> sums,
                             Map<String, Set<LocalDate>> days, LeaderboardRange range) {
        int active = 0;
        long total = 0;
        int participating = 0;
        for (Person person : members) {
            Long xp = sums.get(person.id());
            if (xp != null) {
                active++;
                total += xp;
            }
            if (days.getOrDefault(person.id(), Set.of()).stream().anyMatch(range::contains)) {
                participating++;
            }
        }
        double average = active == 0 ? 0 : Math.round(total * 10.0 / active) / 10.0;
        return new Unit(orgId, members.size(), average, Math.round(participating * 100f / members.size()));
    }
}
