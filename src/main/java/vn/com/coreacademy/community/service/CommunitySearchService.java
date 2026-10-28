package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.search.SearchContributor;
import vn.com.coreacademy.common.search.SearchQueryRepository;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.dto.SearchDtos.SearchHit;
import vn.com.coreacademy.community.dto.SearchDtos.SearchPageView;
import vn.com.coreacademy.community.exception.CommunityResponseCode;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunitySearchService {
    private final List<SearchContributor> contributors;
    private final SearchQueryRepository search;
    private final CommunityPolicy policy;
    private final CommunityGuildService guilds;

    public SearchPageView search(String q, List<String> sourceTypes, String kind, List<Long> tagIds, Long guildId,
                                 int page, int size, String sort) {
        policy.actor();
        CommunityPages.check(page, size);
        var sources = sourceTypes == null ? List.of("community", "learning", "knowledge") : sourceTypes;
        if (q == null || q.isBlank() || q.length() > 300 || sources.isEmpty() || sources.size() > 3
            || !Set.of("community", "learning", "knowledge").containsAll(sources)
            || new HashSet<>(sources).size() != sources.size() || tagIds != null && tagIds.size() > 10
            || kind != null && !CommunityCodes.KINDS.contains(kind)) {
            throw new BusinessException(CommunityResponseCode.INVALID_REFERENCE);
        }
        if (guildId != null) {
            guilds.requireFeed(guildId);
        }
        boolean communityOnly = guildId != null || kind != null || tagIds != null && !tagIds.isEmpty();
        var filter = new SearchContributor.Filter(q.trim(), tagIds, guildId, kind);
        List<SearchContributor.Part> parts = new ArrayList<>();
        for (var contributor : contributors) {
            if (sources.contains(contributor.sourceType()) && (!communityOnly
                || "community".equals(contributor.sourceType()))) {
                parts.add(contributor.contribute(filter));
            }
        }
        var result = search.search(parts, page, size, sort);
        return new SearchPageView(result.items().stream().map(this::hit).toList(), page, size, result.totalElements(),
            result.totalPages(), parts.stream().map(SearchContributor.Part::sourceType).sorted().toList());
    }

    private SearchHit hit(Object[] row) {
        return new SearchHit(row[0].toString(), ((Number) row[1]).longValue(), nullableLong(row[2]), row[3].toString(),
            HtmlUtils.htmlEscape(row[4] == null ? "" : row[4].toString()), ((Number) row[5]).doubleValue(),
            row[6] == null ? null : Instant.ofEpochMilli(((Number) row[6]).longValue() * 1_000), row[7].toString(),
            null, List.of(), nullableLong(row[8]), List.of("read"));
    }

    private Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
