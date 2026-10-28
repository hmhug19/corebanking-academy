package vn.com.coreacademy.community.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.dto.AnalyticsDtos.ActivityPoint;
import vn.com.coreacademy.community.dto.AnalyticsDtos.AnalyticsSummary;
import vn.com.coreacademy.community.dto.AnalyticsDtos.Period;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.entity.ContentView;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.community.repository.CommunityAnalyticsRepository;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.ContentRepository;
import vn.com.coreacademy.community.repository.ContentRepository.DocumentFilter;
import vn.com.coreacademy.identity.service.IdentityWriteLock;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityAnalyticsService {
    private final CommunityAnalyticsRepository analytics;
    private final CommunityRepository repository;
    private final CommunityPolicy policy;
    private final CommunityContentService contents;
    private final CommunityGuildService guilds;
    private final IdentityWriteLock writeLock;

    public AnalyticsSummary overview(Instant from, Instant to, Long guildId) {
        var actor = policy.actor();
        Period period = period(from, to);
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        var row = analytics.overview(actor, period.from(), period.to(), guildId);
        long questions = number(row[0]);
        long solved = number(row[3]);
        return new AnalyticsSummary(period, questions, number(row[1]), number(row[2]), solved,
            questions == 0 ? null : (double) solved / questions,
            row[4] == null ? null : ((Number) row[4]).doubleValue(), number(row[5]), number(row[6]), number(row[7]));
    }

    public List<ActivityPoint> activity(Instant from, Instant to, String bucket, Long guildId) {
        var actor = policy.actor();
        Period period = period(from, to);
        if (!Set.of("day", "week").contains(bucket)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        return analytics.activity(actor, period.from(), period.to(), guildId, bucket).stream().map(row ->
            new ActivityPoint(Instant.ofEpochSecond(number(row[0])), Instant.ofEpochSecond(number(row[1])),
                number(row[2]), number(row[3]), number(row[4]), number(row[5]), number(row[6]))).toList();
    }

    public PageResponse<ContentCard> unanswered(int olderThanDays, Long guildId, int page, int size) {
        if (olderThanDays < 0 || olderThanDays > 36_500) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        return contents.list(new ContentRepository.Search("question", null, null, "unanswered", null, false,
            null, null, page, size, "publishedAt", guildId,
            new DocumentFilter(null, false, null, null, null, null, null, null, null, olderThanDays, false)));
    }

    /** Đếm theo UTC và grain user/content/ngày; refresh chỉ cập nhật lastViewAt. */
    @Transactional
    public void view(Long contentId) {
        writeLock.acquire();
        var actor = policy.actor();
        var content = policy.readable(contentId, actor);
        contents.requirePublished(content);
        Instant now = Instant.now();
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        var previous = analytics.view(contentId, actor.person().id(), today);
        ContentView view;
        if (previous.isEmpty()) {
            view = new ContentView();
            view.setContentId(contentId);
            view.setUserId(actor.person().id());
            view.setViewDate(today);
            view.setFirstViewAt(now);
        } else {
            view = previous.get(0);
        }
        view.setLastViewAt(now);
        repository.save(view, actor.person().id());
    }

    public static Period period(Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(Duration.ofDays(30)) : from;
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0) {
            throw BusinessException.validation("from", "INVALID_RANGE", "Khoảng thời gian phải trong 1..366 ngày.");
        }
        return new Period(start, end, "UTC");
    }

    private long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
