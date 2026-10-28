package vn.com.coreacademy.community.dto;

import java.time.Instant;

public final class AnalyticsDtos {
    public record Period(Instant from, Instant to, String timeZone) {
    }

    public record AnalyticsSummary(Period period, long questions, long unanswered, long answered, long solved,
                                    Double solvedRatio, Double firstResponseMedianMs, long activeContributors,
                                    long uniqueViews, long staleContents) {
    }

    public record ActivityPoint(Instant bucketStart, Instant bucketEnd, long publishedQuestions, long publishedArticles,
                                long publishedAnswers, long uniqueContributors, long uniqueViews) {
    }

    private AnalyticsDtos() {
    }
}
