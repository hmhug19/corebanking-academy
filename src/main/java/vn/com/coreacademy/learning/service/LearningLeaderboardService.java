package vn.com.coreacademy.learning.service;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Person;
import vn.com.coreacademy.learning.constants.LearningCodes.LeaderboardScope;
import vn.com.coreacademy.learning.constants.LearningCodes.TargetType;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningPointsViews.LeaderboardPage;
import vn.com.coreacademy.learning.dto.LearningPointsViews.LeaderboardRow;
import vn.com.coreacademy.learning.dto.LearningPointsViews.MyPoints;
import vn.com.coreacademy.learning.exception.LearningResponseCode;

/**
 * Bảng xếp hạng điểm học tập theo nhóm, đơn vị hoặc toàn hệ thống. Người được xếp hạng là học viên đủ điều kiện học
 * (cùng điều kiện với giao học, lấy từ Identity); điểm lấy từ LearningPointsService nên bảng và thẻ điểm không lệch
 * nhau. Toàn bộ phạm vi được tính rồi mới phân trang vì hạng phụ thuộc điểm của mọi người.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningLeaderboardService {
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningPointsService points;

    private record Ranked(Person person, MyPoints score) {
    }

    /**
     * Điểm giảm dần, cùng điểm xếp theo tên rồi mã; cùng điểm chung hạng và hạng kế tiếp nhảy cóc (1, 1, 3). Người
     * 0 điểm tự nằm cuối vì không ai thấp hơn, theo tên. Dòng me luôn có; rank của me là null nếu người gọi không
     * thuộc phạm vi (xem nhóm khi chưa có nhóm), khi đó trang rỗng.
     */
    public LeaderboardPage leaderboard(String scope, int page, int size) {
        var actor = access.actor();
        access.requireLearner(actor);
        Person caller = actor.person();
        List<Person> members = members(scope, caller);

        Set<String> ids = new HashSet<>();
        members.forEach(person -> ids.add(person.id()));
        ids.add(caller.id());
        Map<String, MyPoints> scores = points.scores(ids);

        Collator collator = Collator.getInstance(Locale.forLanguageTag("vi"));
        List<Ranked> ranked = new ArrayList<>(members.stream().map(p -> new Ranked(p, scores.get(p.id()))).toList());
        ranked.sort(Comparator.<Ranked>comparingLong(r -> -r.score().total())
            .thenComparing(r -> r.person().name(), collator).thenComparing(r -> r.person().id()));
        int[] ranks = new int[ranked.size()];
        for (int i = 0; i < ranked.size(); i++) {
            boolean tied = i > 0 && ranked.get(i).score().total() == ranked.get(i - 1).score().total();
            ranks[i] = tied ? ranks[i - 1] : i + 1;
        }

        int from = (int) Math.min((long) page * size, ranked.size());
        int to = Math.min(from + size, ranked.size());
        Set<Long> teamIds = new HashSet<>();
        ranked.subList(from, to).forEach(r -> addTeam(teamIds, r.person()));
        addTeam(teamIds, caller);
        Map<Long, String> teamNames = teamIds.isEmpty() ? Map.of() : identity.teamNames(teamIds);

        List<LeaderboardRow> items = new ArrayList<>();
        for (int i = from; i < to; i++) {
            items.add(row(ranks[i], ranked.get(i).person(), ranked.get(i).score(), teamNames, caller));
        }
        Integer callerRank = null;
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).person().id().equals(caller.id())) {
                callerRank = ranks[i];
            }
        }
        LeaderboardRow me = row(callerRank, caller, scores.get(caller.id()), teamNames, caller);
        return new LeaderboardPage(PageResponse.from(new PageImpl<>(items, PageRequest.of(page, size),
            ranked.size())), me);
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

    private static LeaderboardRow row(Integer rank, Person person, MyPoints score, Map<Long, String> teamNames,
                                      Person caller) {
        return new LeaderboardRow(rank, person.id(), person.name(),
            person.teamId() == null ? null : teamNames.get(person.teamId()), score.total(), score.streakDays(),
            person.id().equals(caller.id()));
    }
}
