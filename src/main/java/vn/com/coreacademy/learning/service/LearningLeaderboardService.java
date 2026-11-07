package vn.com.coreacademy.learning.service;

import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
import vn.com.coreacademy.learning.constants.LearningCodes.TargetType;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningPointsViews.Leaderboard;
import vn.com.coreacademy.learning.dto.LearningPointsViews.LeaderboardRow;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.LearningPointsRepository;

/**
 * Bảng xếp hạng XP theo kỳ (tuần, tháng, tất cả) và phạm vi nhóm, đơn vị hoặc toàn hệ thống. Người được xếp hạng là học
 * viên đủ điều kiện học (cùng điều kiện với giao học, lấy từ Identity); XP lấy từ sổ ghi XP nên kỳ "tất cả" khớp thẻ
 * XP. Toàn bộ phạm vi được tính vì hạng phụ thuộc XP của mọi người, nhưng chỉ trả vài dòng.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningLeaderboardService {
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningPointsRepository points;
    private final LearningStreakService streaks;

    private record Ranked(Person person, long xp) {
    }

    /**
     * XP giảm dần trong kỳ, cùng XP xếp theo tên rồi mã; cùng XP chung hạng và hạng kế tiếp nhảy cóc (1, 1, 3). XP của
     * kỳ là tổng các dòng sổ có occurred_at trong kỳ theo giờ Asia/Ho_Chi_Minh. Trả 10 người đầu, dòng me luôn có và,
     * khi me ngoài top, 1-2 người ngay trên me cùng số XP còn thiếu. Không phân trang, không tổng số người.
     */
    public Leaderboard leaderboard(String scope, String period, int offset) {
        var actor = access.actor();
        access.requireLearner(actor);
        Person caller = actor.person();
        LocalDate today = LearningActivityService.localDate(Instant.now());
        LeaderboardRange range = LeaderboardRange.of(period, offset, today);
        List<Person> members = members(scope, caller);

        Set<String> ids = new HashSet<>();
        members.forEach(person -> ids.add(person.id()));
        ids.add(caller.id());
        Map<String, Long> sums = points.sumsBetween(ids, range.fromInstant(), range.endInstant());

        Collator collator = Collator.getInstance(Locale.forLanguageTag("vi"));
        List<Ranked> ranked = new ArrayList<>(members.stream()
            .map(p -> new Ranked(p, sums.getOrDefault(p.id(), 0L))).toList());
        ranked.sort(Comparator.<Ranked>comparingLong(r -> -r.xp())
            .thenComparing(r -> r.person().name(), collator).thenComparing(r -> r.person().id()));
        int[] ranks = new int[ranked.size()];
        int mine = -1;
        for (int i = 0; i < ranked.size(); i++) {
            boolean tied = i > 0 && ranked.get(i).xp() == ranked.get(i - 1).xp();
            ranks[i] = tied ? ranks[i - 1] : i + 1;
            if (ranked.get(i).person().id().equals(caller.id())) {
                mine = i;
            }
        }
        long myXp = sums.getOrDefault(caller.id(), 0L);

        int topCount = Math.min(LearningLimits.LEADERBOARD_TOP, ranked.size());
        boolean outside = mine >= topCount;
        List<Integer> shown = new ArrayList<>();
        for (int i = 0; i < topCount; i++) {
            shown.add(i);
        }
        for (int i = outside ? Math.max(topCount, mine - LearningLimits.LEADERBOARD_ABOVE) : mine; outside && i < mine;
             i++) {
            shown.add(i);
        }
        Set<Long> teamIds = new HashSet<>();
        Set<String> shownIds = new HashSet<>();
        shown.forEach(i -> {
            addTeam(teamIds, ranked.get(i).person());
            shownIds.add(ranked.get(i).person().id());
        });
        addTeam(teamIds, caller);
        shownIds.add(caller.id());
        Map<Long, String> teamNames = teamIds.isEmpty() ? Map.of() : identity.teamNames(teamIds);
        Map<String, Integer> streakDays = streaks.currentStreaks(shownIds, today);

        List<LeaderboardRow> top = new ArrayList<>();
        List<LeaderboardRow> above = new ArrayList<>();
        for (int i : shown) {
            Ranked r = ranked.get(i);
            LeaderboardRow row = row(ranks[i], r.person(), r.xp(), streakDays, teamNames, caller);
            (i < topCount ? top : above).add(row);
        }
        Integer callerRank = mine < 0 ? null : ranks[mine];
        LeaderboardRow me = row(callerRank, caller, myXp, streakDays, teamNames, caller);
        Long gap = null;
        if (outside) {
            for (int i = mine - 1; i >= 0; i--) {
                if (ranked.get(i).xp() > myXp) {
                    gap = ranked.get(i).xp() - myXp;
                    break;
                }
            }
        }
        return new Leaderboard(period, range.offset(), range.from(), range.to(), top, me, above, gap);
    }

    private List<Person> members(String scope, Person caller) {
        int limit = LearningLimits.MAX_LEADERBOARD_LEARNERS;
        List<Person> found = switch (scope) {
            case LeaderboardScope.TEAM -> caller.teamId() == null || caller.orgId() == null ? List.of()
                : identity.rankableLearners(TargetType.TEAM, Set.of(caller.orgId()), caller.teamId(), limit);
            case LeaderboardScope.ORG -> caller.orgId() == null ? List.of()
                : identity.rankableLearners(TargetType.ORG, Set.of(caller.orgId()), null, limit);
            case LeaderboardScope.ALL -> identity.rankableLearners(LeaderboardScope.ALL, Set.of(), null, limit);
            default -> throw new IllegalArgumentException("Unknown leaderboard scope");
        };
        if (found.size() > limit) {
            throw new BusinessException(LearningResponseCode.LEADERBOARD_TOO_LARGE);
        }
        return found;
    }

    private static void addTeam(Set<Long> teamIds, Person person) {
        if (person.teamId() != null) {
            teamIds.add(person.teamId());
        }
    }

    private static LeaderboardRow row(Integer rank, Person person, long xp, Map<String, Integer> streakDays,
                                      Map<Long, String> teamNames, Person caller) {
        return new LeaderboardRow(rank, person.id(), person.name(),
            person.teamId() == null ? null : teamNames.get(person.teamId()), xp,
            streakDays.getOrDefault(person.id(), 0), person.id().equals(caller.id()));
    }
}
