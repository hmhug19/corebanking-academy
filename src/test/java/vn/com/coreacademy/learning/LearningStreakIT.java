package vn.com.coreacademy.learning;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.learning.dto.LearningStreakViews.HeatmapDay;
import vn.com.coreacademy.learning.dto.LearningStreakViews.MyStreak;
import vn.com.coreacademy.learning.service.LearningStreakService;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/**
 * Chuỗi ngày học theo ngày làm việc qua cuối tuần, ngày nghỉ và ngày làm bù. Tuần thử nghiệm nằm năm 2030 để không
 * đụng ngày lễ đã gieo; người trong test là mã giả (bảng không có FK). 2030-01-07 là thứ Hai.
 */
class LearningStreakIT extends AssessmentIntegrationTest {
    private static final LocalDate MON = LocalDate.of(2030, 1, 7);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate WED = MON.plusDays(2);
    private static final LocalDate THU = MON.plusDays(3);
    private static final LocalDate FRI = MON.plusDays(4);
    private static final LocalDate SAT = MON.plusDays(5);
    private static final LocalDate NEXT_MON = MON.plusDays(7);
    private static final LocalDate NEXT_TUE = MON.plusDays(8);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LearningStreakService streaks;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private Course course;
    private List<Long> questions;
    private int counter;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        course = fixtures.course(LEARNER);
        long competency = fixtures.competency();
        questions = List.of(fixtures.question(course.id(), competency, "SINGLE", 0));
    }

    @AfterEach
    void clearTestCalendar() {
        jdbc.update("delete from learning_hub.working_calendar_days where calendar_date >= '2030-01-01'");
    }

    private void calendar(LocalDate day, String type) {
        jdbc.update("insert into learning_hub.working_calendar_days (calendar_date, day_type) values (?, ?)", day,
            type);
    }

    private String user() {
        return "ST-" + (++counter) + "-" + (System.nanoTime() % 100000);
    }

    /** Một bài học hoàn thành lúc 10:00 giờ Việt Nam của ngày đó. */
    private void lesson(String user, LocalDate day) {
        long enrollment = jdbc.queryForObject("""
            insert into learning_hub.course_enrollments (course_id, revision_id, user_id, cycle_no, enrollment_origin,
                enrolled_at, status, progress_percent, created_by, updated_by)
            values (?, ?, ?, ?, 'assigned', now(), 'in_progress', 0, ?, ?) returning enrollment_id
            """, Long.class, course.id(), course.revisionId(), user, ++counter, user, user);
        jdbc.update("""
            insert into learning_hub.lesson_progress (enrollment_id, lesson_id, status, progress_percent, completed_at,
                created_by, updated_by)
            values (?, ?, 'completed', 100, (?::timestamp at time zone 'Asia/Ho_Chi_Minh'), 'ST', 'ST')
            """, enrollment, course.lessonId(), day.atTime(10, 0));
    }

    private void quiz(String user, LocalDate day, String status) {
        var body = quizBody(course.id());
        body.put("questionCount", 1);
        long quizId = fixtures.quiz(body, questions, true);
        jdbc.update("""
            insert into learning_hub.quiz_attempts (quiz_id, user_id, enrollment_id, attempt_no, status, started_at,
                deadline_at, submitted_at, graded_at, time_limit_minutes, pass_percent, scoring_mode, grace_seconds,
                total_questions, max_score, passed)
            select ?, ?, 0, 1, ?, t - interval '1 hour', t + interval '1 hour', t, t, 30, 70, 'ALL_OR_NOTHING', 0, 1,
                1, false
            from (select (?::timestamp at time zone 'Asia/Ho_Chi_Minh') as t) x
            """, quizId, user, status, day.atTime(11, 0));
    }

    private MyStreak streak(String user, LocalDate today) {
        return streaks.streak(user, today);
    }

    @Test
    void openingALessonOrAPageWithoutCompletingAnythingIsNotAQualifyingDay() {
        String user = user();
        jdbc.update("insert into learning_hub.learning_activity_days (user_id, activity_date, first_source)"
            + " values (?, ?, 'session')", user, THU);
        jdbc.update("insert into learning_hub.learning_activity_days (user_id, activity_date, first_source)"
            + " values (?, ?, 'block_progress')", user, FRI);

        MyStreak result = streak(user, FRI);

        assertThat(result.currentStreak()).isZero();
        assertThat(result.longestStreak()).isZero();
        assertThat(day(result, THU).lessons()).isZero();
    }

    @Test
    void aLessonOrASubmittedQuizQualifiesButAVoidedAttemptDoesNot() {
        String user = user();
        lesson(user, THU);
        quiz(user, FRI, "SUBMITTED");
        quiz(user, NEXT_MON, "VOIDED");

        MyStreak result = streak(user, FRI);

        assertThat(result.currentStreak()).isEqualTo(2);
        assertThat(day(result, THU).lessons()).isEqualTo(1);
        assertThat(day(result, FRI).quizzes()).isEqualTo(1);
        assertThat(day(streak(user, NEXT_MON), NEXT_MON).quizzes()).as("voided attempt shows nothing").isZero();
    }

    @Test
    void aWeekendBetweenTwoWorkingDaysKeepsTheStreak() {
        String user = user();
        lesson(user, THU);
        lesson(user, FRI);
        lesson(user, NEXT_MON);

        MyStreak result = streak(user, NEXT_MON);

        assertThat(result.currentStreak()).isEqualTo(3);
        assertThat(result.longestStreak()).isEqualTo(3);
    }

    @Test
    void learningOnADayOffIsShownButAddsNothing() {
        String user = user();
        lesson(user, FRI);
        lesson(user, SAT);
        lesson(user, NEXT_MON);

        MyStreak result = streak(user, NEXT_MON);

        assertThat(result.currentStreak()).as("Friday and Monday only").isEqualTo(2);
        HeatmapDay saturday = day(result, SAT);
        assertThat(saturday.lessons()).isEqualTo(1);
        assertThat(saturday.restDay()).isTrue();
    }

    @Test
    void aHolidayInTheMiddleOfTheWeekNeitherCountsNorBreaksTheStreak() {
        calendar(WED, "HOLIDAY");
        String user = user();
        lesson(user, TUE);
        lesson(user, THU);

        MyStreak result = streak(user, THU);

        assertThat(result.currentStreak()).isEqualTo(2);
        assertThat(day(result, WED).restDay()).isTrue();
    }

    @Test
    void aMissedWorkingDayBreaksTheStreakAndTheLongestStaysSeparate() {
        String user = user();
        lesson(user, MON);
        lesson(user, TUE);
        lesson(user, WED);
        lesson(user, FRI);

        MyStreak result = streak(user, FRI);

        assertThat(result.currentStreak()).isEqualTo(1);
        assertThat(result.longestStreak()).isEqualTo(3);
    }

    @Test
    void aMakeUpSaturdayIsAWorkingDay() {
        calendar(SAT, "MAKEUP_WORKDAY");
        String counted = user();
        lesson(counted, FRI);
        lesson(counted, SAT);
        lesson(counted, NEXT_MON);
        String missed = user();
        lesson(missed, FRI);
        lesson(missed, NEXT_MON);

        assertThat(streak(counted, NEXT_MON).currentStreak()).isEqualTo(3);
        assertThat(streak(missed, NEXT_MON).currentStreak()).as("the make-up Saturday was skipped").isEqualTo(1);
        assertThat(day(streak(counted, NEXT_MON), SAT).restDay()).isFalse();
    }

    @Test
    void anEmptyTodayDoesNotBreakTheStreakUntilTheDayEnds() {
        String user = user();
        lesson(user, THU);
        lesson(user, FRI);

        assertThat(streak(user, NEXT_MON).currentStreak()).as("Monday is still open").isEqualTo(2);
        assertThat(streak(user, NEXT_TUE).currentStreak()).as("Monday passed with nothing").isZero();
        assertThat(streak(user, NEXT_TUE).longestStreak()).isEqualTo(2);
    }

    @Test
    void theHeatmapCoversTwentySixWeeksFromAMondayToToday() {
        String user = user();

        MyStreak result = streak(user, NEXT_TUE);

        assertThat(result.days().get(0).date().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(result.days().get(result.days().size() - 1).date()).isEqualTo(NEXT_TUE);
        assertThat(result.days().get(0).date()).isEqualTo(NEXT_TUE.minusWeeks(25).with(DayOfWeek.MONDAY));
        assertThat(result.days()).hasSize(25 * 7 + 2);
        assertThat(result.days()).extracting(HeatmapDay::date).doesNotHaveDuplicates();
    }

    @Test
    void theEndpointReturnsTheShapeAndRefusesANonLearner() {
        JsonNode mine = api.ok(LEARNER, get("/api/v1/me/learning/streak"));

        assertThat(mine.fieldNames()).toIterable().containsExactly("currentStreak", "longestStreak", "days");
        assertThat(mine.path("days").get(0).fieldNames()).toIterable()
            .containsExactly("date", "lessons", "quizzes", "restDay");
        api.http(ADMIN, get("/api/v1/me/learning/streak"), 403, "CORE-ACCESS_DENIED");
    }

    private static HeatmapDay day(MyStreak result, LocalDate date) {
        return result.days().stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow();
    }
}
