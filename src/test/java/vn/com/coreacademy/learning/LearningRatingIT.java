package vn.com.coreacademy.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.learning.repository.LessonFeedbackReadRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/** Đánh giá sao F2 và điểm đánh giá của khóa trên thẻ F5: quyền vào bài, thay thế tại chỗ, đồng thời, làm tròn. */
class LearningRatingIT extends AssessmentIntegrationTest {
    private static final String RATING = "/api/v1/me/learning/lessons/{lesson}/rating";
    private static final String LEARNING = "/api/v1/me/learning";
    private static final String NOT_FOUND = "LEARNING-NOT_FOUND";
    private static final String VALIDATION = "CORE-VALIDATION_FAILED";

    @MockitoSpyBean
    private LessonFeedbackReadRepository reads;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
    }

    @Test
    void ratingAgainReplacesTheLiveRatingInPlace() {
        Course course = fixtures.course(LEARNER);

        JsonNode first = api.ok(LEARNER, api.put(stars(4), RATING, course.lessonId()));
        assertThat(first.fieldNames()).toIterable().containsExactly("average", "count", "myStars");
        assertThat(first.path("average").decimalValue()).isEqualByComparingTo("4.0");
        assertThat(first.path("count").asLong()).isEqualTo(1);
        assertThat(first.path("myStars").asInt()).isEqualTo(4);

        JsonNode second = api.ok(LEARNER, api.put(stars(5), RATING, course.lessonId()));
        assertThat(second.path("average").decimalValue()).isEqualByComparingTo("5.0");
        assertThat(second.path("count").asLong()).isEqualTo(1);
        assertThat(second.path("myStars").asInt()).isEqualTo(5);

        assertThat(liveRatings(LEARNER, course.lessonId())).as("one live row").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            select count(*) from learning_hub.lesson_feedback where user_id = ? and lesson_id = ?
            """, Integer.class, LEARNER, course.lessonId())).as("replaced, not added").isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("""
            select stars, course_id, created_by, updated_by, row_version, body, anchor_type, status
            from learning_hub.lesson_feedback where user_id = ? and lesson_id = ?
            """, LEARNER, course.lessonId());
        assertThat(((Number) row.get("stars")).intValue()).isEqualTo(5);
        assertThat(row.get("course_id")).isEqualTo(course.id());
        assertThat(row.get("created_by")).isEqualTo(LEARNER);
        assertThat(row.get("updated_by")).isEqualTo(LEARNER);
        assertThat(((Number) row.get("row_version")).longValue()).as("second PUT updated the row").isEqualTo(1);
        assertThat(row.get("body")).isNull();
        assertThat(row.get("anchor_type")).isNull();
        assertThat(row.get("status")).isNull();
    }

    @Test
    void refusesStarsOutsideOneToFive() {
        Course course = fixtures.course(LEARNER);

        for (int invalid : new int[] {0, 6, -1, 100}) {
            JsonNode body = api.error(LEARNER, api.put(stars(invalid), RATING, course.lessonId()), VALIDATION);
            assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("stars");
            assertThat(body.path("errors").get(0).path("message").asText()).isEqualTo("Số sao phải từ 1 đến 5.");
        }
        api.error(LEARNER, api.put("{}", RATING, course.lessonId()), VALIDATION);
        api.error(LEARNER, api.put("{\"stars\":\"năm\"}", RATING, course.lessonId()), VALIDATION);
        assertThat(liveRatings(LEARNER, course.lessonId())).as("nothing was saved").isZero();

        api.ok(LEARNER, api.put(stars(1), RATING, course.lessonId()));
        api.ok(LEARNER, api.put(stars(5), RATING, course.lessonId()));
    }

    @Test
    void concurrentRatingsFromOneUserLeaveOneLiveRow() throws Exception {
        Course course = fixtures.course(LEARNER);
        int calls = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        try {
            List<Future<JsonNode>> results = new ArrayList<>();
            for (int i = 1; i <= calls; i++) {
                int value = i % 5 + 1;
                results.add(pool.submit(() -> {
                    start.await();
                    return api.ok(LEARNER, api.put(stars(value), RATING, course.lessonId()));
                }));
            }
            start.countDown();
            for (Future<JsonNode> result : results) {
                assertThat(result.get().path("count").asLong()).as("every call saw one rating").isEqualTo(1);
            }

            assertThat(liveRatings(LEARNER, course.lessonId())).as("one live row").isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                select count(*) from learning_hub.lesson_feedback where user_id = ? and lesson_id = ?
                """, Integer.class, LEARNER, course.lessonId())).as("never a second row").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void refusesALessonOutsideTheCallersEnrollments() {
        Course course = fixtures.course(LEARNER);
        Course withdrawn = fixtures.course(LEARNER);
        jdbc.update("update learning_hub.course_enrollments set status = 'withdrawn' where course_id = ?",
            withdrawn.id());

        api.error(LEARNER_2, api.put(stars(4), RATING, course.lessonId()), NOT_FOUND);
        api.error(LEARNER, api.put(stars(4), RATING, withdrawn.lessonId()), NOT_FOUND);
        api.error(LEARNER, api.put(stars(4), RATING, 999_999_999L), NOT_FOUND);
        assertThat(jdbc.queryForObject("""
            select count(*) from learning_hub.lesson_feedback where lesson_id in (?, ?)
            """, Integer.class, course.lessonId(), withdrawn.lessonId())).isZero();
    }

    @Test
    void onlyLearnersMayRate() {
        Course course = fixtures.course(LEARNER);

        api.http(ADMIN, api.put(stars(4), RATING, course.lessonId()), 403, "CORE-ACCESS_DENIED");
        api.http(null, api.put(stars(4), RATING, course.lessonId()), 401, "CORE-UNAUTHENTICATED");
    }

    @Test
    void theLessonAverageCoversEveryLearnerAndRoundsToOneDecimal() {
        Course course = fixtures.course(LEARNER, LEARNER_2);
        for (String fake : new String[] {"F1", "F2", "F3"}) {
            insertRating(fake, course.lessonId(), course.id(), 4);
        }

        api.ok(LEARNER_2, api.put(stars(4), RATING, course.lessonId()));
        JsonNode result = api.ok(LEARNER, api.put(stars(5), RATING, course.lessonId()));

        assertThat(result.path("count").asLong()).isEqualTo(5);
        assertThat(result.path("average").decimalValue()).as("(4+4+4+4+5)/5").isEqualByComparingTo("4.2");

        JsonNode other = api.ok(LEARNER_2, api.put(stars(5), RATING, course.lessonId()));
        assertThat(other.path("average").decimalValue()).as("4.4 for (4+4+4+5+5)/5").isEqualByComparingTo("4.4");
        assertThat(other.path("myStars").asInt()).isEqualTo(5);
    }

    @Test
    void theCardCarriesTheCourseRatingOverEveryLessonLearnerAndRevision() {
        Course course = fixtures.course(LEARNER, LEARNER_2);
        long second = secondLesson(course);
        api.ok(LEARNER, api.put(stars(5), RATING, course.lessonId()));
        api.ok(LEARNER, api.put(stars(4), RATING, second));
        api.ok(LEARNER_2, api.put(stars(4), RATING, course.lessonId()));

        for (String list : List.of("/courses", "/catalog")) {
            for (String user : List.of(LEARNER, LEARNER_2)) {
                assertRating(card(user, LEARNING + list, course).path("rating"), "4.3", 3, user + " " + list);
            }
        }
        assertRating(api.ok(LEARNER, get(LEARNING + "/courses/{c}", course.id())).path("card").path("rating"), "4.3",
            3, "/courses/{id}");
        assertRating(card(INSTRUCTOR, "/api/v1/instructor/courses", course).path("rating"), "4.3", 3,
            "the instructor list");

        // Khóa có phiên bản cũ: đánh giá gắn lesson_id của phiên bản khác nhưng cùng course_id vẫn tính.
        insertRating("F1", 999_999_001L, course.id(), 5);
        assertRating(card(LEARNER, LEARNING + "/courses", course).path("rating"), "4.5", 4, "an older revision");
        assertRating(api.ok(LEARNER, get(LEARNING + "/courses/{c}", course.id())).path("card").path("rating"), "4.5",
            4, "/courses/{id} with an older revision");
    }

    @Test
    void aCourseWithoutRatingsHasANullRatingAndADeletedRatingDoesNotCount() {
        Course unrated = fixtures.course(LEARNER);
        Course deleted = fixtures.course(LEARNER);
        insertRating("F1", deleted.lessonId(), deleted.id(), 1);
        jdbc.update("update learning_hub.lesson_feedback set is_deleted = true where course_id = ?", deleted.id());

        for (Course course : List.of(unrated, deleted)) {
            JsonNode card = card(LEARNER, LEARNING + "/courses", course);
            assertThat(card.has("rating")).as("the key is always there").isTrue();
            assertThat(card.path("rating").isNull()).isTrue();
            assertThat(card(LEARNER, LEARNING + "/catalog", course).path("rating").isNull()).isTrue();
        }
        assertThat(api.ok(LEARNER, get(LEARNING + "/courses/{c}", unrated.id())).path("card").path("rating")
            .isNull()).isTrue();
    }

    @Test
    void aPageCostsOneGroupedRatingsQuery() {
        fixtures.course(LEARNER);
        fixtures.course(LEARNER);
        clearInvocations(reads);

        JsonNode page = api.ok(LEARNER, get(LEARNING + "/courses?size=100"));

        assertThat(page.path("items").size()).isGreaterThanOrEqualTo(2);
        verify(reads, times(1)).courseRatings(any());
    }

    private void assertRating(JsonNode rating, String average, long count, String where) {
        assertThat(rating.fieldNames()).as(where).toIterable().containsExactly("average", "count");
        assertThat(rating.path("average").decimalValue()).as(where).isEqualByComparingTo(average);
        assertThat(rating.path("count").asLong()).as(where).isEqualTo(count);
    }

    /** Bài thứ hai trong cùng chương và phiên bản của khóa. */
    private long secondLesson(Course course) {
        return jdbc.queryForObject("""
            insert into learning_hub.course_lessons (chapter_id, title, lesson_type, estimated_duration_seconds,
                is_required, sort_no, created_by, updated_by)
            select chapter_id, 'Bài hai', lesson_type, estimated_duration_seconds, false, sort_no + 1, created_by,
                updated_by
            from learning_hub.course_lessons where lesson_id = ? returning lesson_id
            """, Long.class, course.lessonId());
    }

    private void insertRating(String userId, long lessonId, long courseId, int stars) {
        jdbc.update("""
            insert into learning_hub.lesson_feedback (user_id, lesson_id, course_id, kind, stars, created_by,
                updated_by)
            values (?, ?, ?, 'rating', ?, ?, ?)
            """, userId, lessonId, courseId, stars, userId, userId);
    }

    private int liveRatings(String userId, long lessonId) {
        return jdbc.queryForObject("""
            select count(*) from learning_hub.lesson_feedback
            where user_id = ? and lesson_id = ? and kind = 'rating' and not is_deleted
            """, Integer.class, userId, lessonId);
    }

    private static String stars(Object value) {
        return "{\"stars\":" + value + "}";
    }

    private JsonNode card(String user, String list, Course course) {
        JsonNode page = api.ok(user, get(list + "?size=100&sort=id,desc"));
        for (JsonNode row : page.path("items")) {
            if (row.path("courseId").asLong() == course.id()) {
                return row;
            }
        }
        throw new AssertionError("Không có khóa " + course.id() + " trong " + list);
    }
}
