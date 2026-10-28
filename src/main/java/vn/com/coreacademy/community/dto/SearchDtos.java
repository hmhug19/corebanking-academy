package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;

public final class SearchDtos {
    public record SearchHit(String sourceType, Long sourceId, Long revisionId, String title, String snippet,
                             double rank, Instant updatedAt, String url, String kind, List<String> tags,
                             Long answerId, List<String> capabilities) {
    }

    public record SearchPageView(List<SearchHit> items, int page, int size, long totalElements, int totalPages,
                                  List<String> appliedSourceTypes) {
    }

    private SearchDtos() {
    }
}
