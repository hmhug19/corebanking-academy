package vn.com.coreacademy.learning;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.identity.service.LearningIdentityService.Person;
import vn.com.coreacademy.learning.dto.LearningProgressOverview;
import vn.com.coreacademy.learning.dto.LearningProgressOverview.AssessmentOverview;
import vn.com.coreacademy.learning.dto.LearningProgressOverview.Availability;
import vn.com.coreacademy.learning.repository.CourseRepository;
import vn.com.coreacademy.learning.repository.custom.CourseRepositoryCustom.EnrollmentCounts;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;
import vn.com.coreacademy.learning.service.LearningProgressReportService;
import vn.com.coreacademy.learning.service.LearningQuizDirectory;
import vn.com.coreacademy.learning.service.LearningQuizDirectory.LastAttempt;
import vn.com.coreacademy.learning.service.LearningQuizDirectory.QuizScore;
import vn.com.coreacademy.learning.service.LearningSearchService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tổng hợp điểm của summary: nguồn là danh sách điểm từng đề của cổng quiz trên đúng các khóa hiện tại. */
class LearningProgressReportServiceTest {
    private static final String USER = "000004";
    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");

    private final LearningQuizDirectory quizzes = mock(LearningQuizDirectory.class);
    private LearningProgressReportService service;

    @BeforeEach
    void setUp() {
        LearningAccessPolicy access = mock(LearningAccessPolicy.class);
        when(access.actor()).thenReturn(new Actor(new Person(USER, "Học viên", 1L, null), List.of()));
        CourseRepository courses = mock(CourseRepository.class);
        when(courses.summary(USER)).thenReturn(new EnrollmentCounts(2L, 0L, 1L, 1L, 0L));
        LearningSearchService search = mock(LearningSearchService.class);
        when(search.mineCourseIds()).thenReturn(List.of(10L, 11L));
        service = new LearningProgressReportService(access, courses, search, quizzes);
    }

    @Test
    void noPublishedQuizMeansUnavailable() {
        when(quizzes.scores(USER, List.of(10L, 11L))).thenReturn(List.of());

        assertThat(service.summary().assessment()).isEqualTo(new Availability("unavailable"));
    }

    @Test
    void quizzesWithoutAnyGradedAttemptAreAvailableWithEmptyFigures() {
        when(quizzes.scores(USER, List.of(10L, 11L))).thenReturn(List.of(
            score(1L, 10L, "Luyện tập", null, null, false, null),
            score(2L, 11L, "Thi cuối khóa", null, null, false, null)));

        AssessmentOverview overview = overview();

        assertThat(overview.availability()).isEqualTo("available");
        assertThat(overview.last()).isNull();
        assertThat(overview.bestScorePercent()).isNull();
        assertThat(overview.averageScorePercent()).isNull();
        assertThat(overview.passedQuizCount()).isZero();
        assertThat(overview.quizCount()).isEqualTo(2);
    }

    @Test
    void aFailedAndAPassedQuizGiveTheBestAverageCountsAndTheLatestAttempt() {
        when(quizzes.scores(USER, List.of(10L, 11L))).thenReturn(List.of(
            score(1L, 10L, "Luyện tập", "14.28", "14.28", false, last(101L, "0", false, T0)),
            score(2L, 10L, "Thi giữa kỳ", "12.5", "12.5", false, last(102L, "12.5", false, T0.plusSeconds(60))),
            score(5L, 11L, "Thi cuối khóa", "100", "100", true, last(105L, "100", true, T0.plusSeconds(30))),
            score(6L, 11L, "Chưa thi", null, null, false, null)));

        AssessmentOverview overview = overview();

        assertThat(overview.bestScorePercent()).isEqualByComparingTo("100");
        assertThat(overview.averageScorePercent()).as("trung bình điểm cuối của 3 đề đã thi")
            .isEqualByComparingTo("42.26");
        assertThat(overview.averageScorePercent().scale()).isEqualTo(2);
        assertThat(overview.passedQuizCount()).isEqualTo(1);
        assertThat(overview.quizCount()).as("gồm cả đề chưa thi").isEqualTo(4);
        assertThat(overview.last().attemptId()).as("lượt nộp muộn nhất, không phải lượt điểm cao nhất")
            .isEqualTo(102L);
        assertThat(overview.last().quizId()).isEqualTo(2L);
        assertThat(overview.last().quizTitle()).isEqualTo("Thi giữa kỳ");
        assertThat(overview.last().courseId()).isEqualTo(10L);
        assertThat(overview.last().scorePercent()).isEqualByComparingTo("12.5");
        assertThat(overview.last().passed()).isFalse();
        assertThat(overview.last().submittedAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void theAverageRoundsHalfUpToTwoDecimals() {
        when(quizzes.scores(USER, List.of(10L, 11L))).thenReturn(List.of(
            score(1L, 10L, "A", "10", "10", false, last(1L, "10", false, T0)),
            score(2L, 11L, "B", "10.01", "10.01", false, last(2L, "10.01", false, T0))));

        assertThat(overview().averageScorePercent()).as("10,005 làm tròn lên").isEqualTo(new BigDecimal("10.01"));
    }

    @Test
    void aLastMethodQuizCountsItsFinalScoreInTheAverageAndItsBestScoreInTheBest() {
        when(quizzes.scores(USER, List.of(10L, 11L))).thenReturn(List.of(
            score(1L, 10L, "LAST", "100", "0", false, last(7L, "0", false, T0))));

        AssessmentOverview overview = overview();

        assertThat(overview.bestScorePercent()).isEqualByComparingTo("100");
        assertThat(overview.averageScorePercent()).isEqualByComparingTo("0");
        assertThat(overview.passedQuizCount()).isZero();
    }

    @Test
    void theScoresAreAskedForTheCallersCurrentCourses() {
        when(quizzes.scores(any(), any())).thenReturn(List.of());

        service.summary();

        verify(quizzes).scores(USER, List.of(10L, 11L));
    }

    @Test
    void anAvailableAssessmentSerialisesWithItsFiguresAndAnUnavailableOneAsAvailabilityOnly() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        when(quizzes.scores(USER, List.of(10L, 11L))).thenReturn(List.of(
            score(1L, 10L, "A", "50", "50", true, last(1L, "50", true, T0))));

        JsonNode available = json.valueToTree(service.summary()).path("assessment");

        assertThat(available.fieldNames()).toIterable().containsExactly("availability", "last", "bestScorePercent",
            "averageScorePercent", "passedQuizCount", "quizCount");
        assertThat(available.path("last").fieldNames()).toIterable().containsExactly("quizId", "quizTitle",
            "courseId", "attemptId", "scorePercent", "passed", "submittedAt");

        when(quizzes.scores(USER, List.of(10L, 11L))).thenReturn(List.of());
        JsonNode unavailable = json.valueToTree(service.summary()).path("assessment");

        assertThat(unavailable.fieldNames()).toIterable().containsExactly("availability");
        assertThat(unavailable.path("availability").asText()).isEqualTo("unavailable");
    }

    private AssessmentOverview overview() {
        LearningProgressOverview summary = service.summary();
        assertThat(summary.assessment()).isInstanceOf(AssessmentOverview.class);
        return (AssessmentOverview) summary.assessment();
    }

    private static QuizScore score(long quizId, long courseId, String title, String best, String finalScore,
                                   boolean passed, LastAttempt last) {
        return new QuizScore(quizId, courseId, title, "PRACTICE", best == null ? null : new BigDecimal(best),
            finalScore == null ? null : new BigDecimal(finalScore), passed, last);
    }

    private static LastAttempt last(long attemptId, String score, boolean passed, Instant submittedAt) {
        return new LastAttempt(attemptId, new BigDecimal(score), passed, submittedAt);
    }
}
