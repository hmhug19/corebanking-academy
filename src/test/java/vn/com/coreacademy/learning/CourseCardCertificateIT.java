package vn.com.coreacademy.learning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/** CourseCard.certificateId is the enrollment id of a completed cycle and null otherwise, on every card the learner sees. */
class CourseCardCertificateIT extends AssessmentIntegrationTest {
    private static final String LEARNING = "/api/v1/me/learning";

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
    void aCompletedCycleCarriesItsEnrollmentIdOnTheCatalogueAndTheCourse() {
        Course course = fixtures.course(LEARNER);
        long enrollment = complete(course, LEARNER);

        for (String list : new String[] {"/courses", "/catalog"}) {
            JsonNode card = card(LEARNER, list, course);
            assertThat(card.has("certificateId")).as(list).isTrue();
            assertThat(card.path("certificateId").asLong()).as(list).isEqualTo(enrollment);
            assertThat(card.path("enrollmentId").asLong()).isEqualTo(enrollment);
        }
        JsonNode single = api.ok(LEARNER, get(LEARNING + "/courses/{c}", course.id())).path("card");
        assertThat(single.path("learningStatus").asText()).isEqualTo("completed");
        assertThat(single.path("certificateId").asLong()).isEqualTo(enrollment);
    }

    @Test
    void anUnfinishedCycleHasNoCertificate() {
        Course course = fixtures.course(LEARNER);
        long enrollment = jdbc.queryForObject("""
            select enrollment_id from learning_hub.course_enrollments where course_id = ? and user_id = ?
            """, Long.class, course.id(), LEARNER);
        jdbc.update("update learning_hub.course_enrollments set status = 'in_progress' where enrollment_id = ?",
            enrollment);

        for (String list : new String[] {"/courses", "/catalog"}) {
            JsonNode card = card(LEARNER, list, course);
            assertThat(card.has("certificateId")).as(list).isTrue();
            assertThat(card.path("certificateId").isNull()).as(list).isTrue();
        }
        JsonNode single = api.ok(LEARNER, get(LEARNING + "/courses/{c}", course.id())).path("card");
        assertThat(single.has("certificateId")).isTrue();
        assertThat(single.path("certificateId").isNull()).isTrue();
    }

    @Test
    void aCourseTheCallerIsNotEnrolledInHasNoCertificate() {
        Course course = fixtures.course();

        JsonNode card = card(LEARNER, "/catalog", course);
        assertThat(card.path("enrollmentId").isNull()).isTrue();
        assertThat(card.has("certificateId")).isTrue();
        assertThat(card.path("certificateId").isNull()).isTrue();
        JsonNode single = api.ok(LEARNER, get(LEARNING + "/courses/{c}", course.id())).path("card");
        assertThat(single.has("certificateId")).isTrue();
        assertThat(single.path("certificateId").isNull()).isTrue();
    }

    @Test
    void anotherLearnersCompletionIsNotMine() {
        Course course = fixtures.course(LEARNER, LEARNER_2);
        long theirs = complete(course, LEARNER_2);

        assertThat(card(LEARNER_2, "/courses", course).path("certificateId").asLong()).isEqualTo(theirs);
        assertThat(card(LEARNER, "/courses", course).path("certificateId").isNull()).isTrue();
    }

    @Test
    void theInstructorListHasTheFieldAndItIsNull() {
        Course course = fixtures.course(LEARNER);
        complete(course, LEARNER);

        JsonNode page = api.ok(INSTRUCTOR, get("/api/v1/instructor/courses?size=100&sort=id,desc"));

        JsonNode found = null;
        for (JsonNode row : page.path("items")) {
            found = row.path("courseId").asLong() == course.id() ? row : found;
        }
        assertThat(found).isNotNull();
        assertThat(found.has("certificateId")).isTrue();
        assertThat(found.path("certificateId").isNull()).as("a manager's card has no cycle").isTrue();
    }

    private long complete(Course course, String user) {
        long id = jdbc.queryForObject("""
            select enrollment_id from learning_hub.course_enrollments where course_id = ? and user_id = ?
            """, Long.class, course.id(), user);
        jdbc.update("""
            update learning_hub.course_enrollments
            set status = 'completed', completed_at = now(), progress_percent = 100 where enrollment_id = ?
            """, id);
        return id;
    }

    private JsonNode card(String user, String list, Course course) {
        for (JsonNode row : api.ok(user, get(LEARNING + list + "?size=100&sort=id,desc")).path("items")) {
            if (row.path("courseId").asLong() == course.id()) {
                return row;
            }
        }
        throw new AssertionError("Course " + course.id() + " is not in " + list);
    }
}
