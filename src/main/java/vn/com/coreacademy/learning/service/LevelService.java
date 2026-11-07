package vn.com.coreacademy.learning.service;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.learning.dto.GamificationConfig;
import vn.com.coreacademy.learning.dto.LearningLevelViews.LevelStep;
import vn.com.coreacademy.learning.dto.LearningLevelViews.MyLevel;
import vn.com.coreacademy.learning.repository.LearningPointsRepository;
import vn.com.coreacademy.learning.repository.LevelRepository;
import vn.com.coreacademy.learning.repository.XpLedgerRepository;

/**
 * Cấp độ theo tổng XP và bậc thang của phiên bản cấu hình đang áp dụng. Cấp cao nhất từng đạt được lưu lại và chỉ
 * tăng, nên nâng ngưỡng không hạ cấp ai. Mỗi lần ghi XP và mỗi lần đọc cập nhật cấp đã lưu; trước khi áp dụng bậc thang
 * mới phải gọi refreshAll để cấp theo bậc thang cũ được giữ.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class LevelService {
    /** Bậc thang mặc định của phiên bản cấu hình 1. */
    public static final List<LevelStep> DEFAULT_LADDER = List.of(
        new LevelStep(1, "Giao dịch viên tập sự", 0),
        new LevelStep(2, "Giao dịch viên", 200),
        new LevelStep(3, "Chuyên viên", 600),
        new LevelStep(4, "Chuyên viên chính", 1500),
        new LevelStep(5, "Chuyên gia nghiệp vụ", 3500),
        new LevelStep(6, "Chuyên gia Core Banking", 7000));

    private final LevelRepository levels;
    private final LearningPointsRepository points;
    private final XpLedgerRepository config;
    private final LearningAccessPolicy access;

    /** Cấp của caller; chỉ học viên (LEARNER, OWN). */
    public MyLevel mine() {
        var actor = access.actor();
        access.requireLearner(actor);
        return refresh(actor.person().id());
    }

    /** Bậc thang đang áp dụng; phiên bản chưa có bậc thang (dữ liệu cũ) được gieo bậc thang mặc định. */
    public List<LevelStep> currentLadder() {
        var latest = config.latestConfig();
        if (latest.isEmpty()) {
            return DEFAULT_LADDER;
        }
        return ladderOf(latest.get());
    }

    public List<LevelStep> ladderOf(GamificationConfig version) {
        List<LevelStep> ladder = levels.ladder(version.versionId());
        if (ladder.isEmpty()) {
            levels.seedLadder(version.versionId(), DEFAULT_LADDER);
            ladder = levels.ladder(version.versionId());
        }
        return ladder;
    }

    /** Cập nhật cấp đã lưu của một người theo tổng XP hiện tại và trả cấp cao nhất từng đạt. */
    public MyLevel refresh(String userId) {
        long total = points.sumsByKind(List.of(userId)).getOrDefault(userId, Map.of()).values().stream()
            .mapToLong(Long::longValue).sum();
        return refresh(userId, total, currentLadder());
    }

    /** Cập nhật cấp đã lưu của mọi người có XP; gọi trước khi áp dụng một bậc thang mới. */
    public int refreshAll() {
        List<LevelStep> ladder = currentLadder();
        Map<String, Long> totals = levels.totals();
        totals.forEach((userId, total) -> refresh(userId, total, ladder));
        return totals.size();
    }

    private MyLevel refresh(String userId, long total, List<LevelStep> ladder) {
        int computed = ladder.stream().filter(step -> step.minXp() <= total).mapToInt(LevelStep::level).max()
            .orElse(1);
        int stored = levels.raise(userId, computed);
        return view(stored, total, ladder);
    }

    static MyLevel view(int level, long xp, List<LevelStep> ladder) {
        LevelStep current = ladder.stream().filter(step -> step.level() <= level)
            .reduce((first, second) -> second).orElse(ladder.get(0));
        LevelStep next = ladder.stream().filter(step -> step.level() > level).findFirst().orElse(null);
        if (next == null) {
            return new MyLevel(level, current.name(), xp, null, null);
        }
        long span = Math.max(1, next.minXp() - current.minXp());
        int percent = (int) Math.max(0, Math.min(100, (xp - current.minXp()) * 100 / span));
        return new MyLevel(level, current.name(), xp, Math.max(0, next.minXp() - xp), percent);
    }
}
