package vn.com.coreacademy.assessment;

import java.util.Map;

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

/** Khóa học viên thấy kèm nội dung: bản đã nhận, bản xuất bản khi chưa đăng ký và khóa ngoài phạm vi. */
class LearnerCourseIT extends AssessmentIntegrationTest {
    private static final String COURSE = "/api/v1/me/learning/courses/{id}";

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
    void aStartedCourseKeepsItsRevisionAfterANewOneIsPublished() {
        Course course = fixtures.course(LEARNER);
        jdbc.update("update learning_hub.course_enrollments set status = 'in_progress', started_at = now() "
            + "where course_id = ? and user_id = ?", course.id(), LEARNER);
        JsonNode draft = api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/courses/{c}/draft", course.id()));
        long newRevision = draft.path("revision").path("id").asLong();
        assertThat(newRevision).isNotEqualTo(course.revisionId());
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", draft.path("revision").path("rowVersion").asLong()),
            "/api/v1/courses/{c}/revisions/{r}/publish", course.id(), newRevision));

        JsonNode enrolled = api.ok(LEARNER, get(COURSE, course.id()));

        assertThat(enrolled.fieldNames()).toIterable().containsExactly("card", "content");
        assertThat(enrolled.path("card").path("courseId").asLong()).isEqualTo(course.id());
        assertThat(enrolled.path("card").path("revisionId").asLong()).isEqualTo(course.revisionId());
        assertThat(enrolled.path("card").path("enrollmentId").isNumber()).isTrue();
        assertThat(enrolled.path("content").path("revision").path("id").asLong()).isEqualTo(course.revisionId());
        assertThat(blocks(enrolled)).as("đã đăng ký thì có nội dung khối").isNotEmpty();
        assertThat(enrolled.path("card").path("programName").asText()).startsWith("Danh mục ");

        JsonNode other = api.ok(LEARNER_2, get(COURSE, course.id()));
        assertThat(other.path("card").path("revisionId").asLong()).as("chưa đăng ký: bản xuất bản mới")
            .isEqualTo(newRevision);
        assertThat(other.path("content").path("revision").path("id").asLong()).isEqualTo(newRevision);
    }

    @Test
    void aVisibleCourseTheCallerHasNotEnrolledInKeepsTheOutlineAndEmptiesTheBlocks() {
        Course course = fixtures.course();

        JsonNode result = api.ok(LEARNER, get(COURSE, course.id()));

        assertThat(result.path("card").path("enrollmentId").isNull()).isTrue();
        assertThat(result.path("card").path("revisionId").asLong()).isEqualTo(course.revisionId());
        JsonNode content = result.path("content");
        assertThat(content.path("revision").path("id").asLong()).isEqualTo(course.revisionId());
        assertThat(content.path("objectives")).isNotEmpty();
        JsonNode lessons = content.path("chapters").get(0).path("lessons");
        assertThat(lessons).hasSize(1);
        assertThat(lessons.get(0).path("lesson").path("title").asText()).isEqualTo("Bài 1");
        assertThat(lessons.get(0).path("blocks")).isEmpty();
        assertThat(result.toString()).as("không lộ nội dung khối").doesNotContain("Nội dung bài học.");
    }

    @Test
    void refusesACourseOutsideTheCallersScope() {
        Course foreign = fixtures.course();
        long other = jdbc.queryForObject("""
            INSERT INTO learning_hub.orgs(parent_id, org_type, code, name, status, sort_no)
            VALUES (NULL, 'UNIT', ?, 'Đơn vị sở hữu khác', 'active', 1) RETURNING org_id
            """, Long.class, AssessmentFixtures.unique("OWN"));
        jdbc.update("UPDATE learning_hub.courses SET owner_org_id = ? WHERE course_id = ?", other, foreign.id());

        api.error(LEARNER, get(COURSE, foreign.id()), "LEARNING-NOT_FOUND");
        api.error(LEARNER, get(COURSE, 999_999_999L), "LEARNING-NOT_FOUND");
    }

    private static JsonNode blocks(JsonNode result) {
        return result.path("content").path("chapters").get(0).path("lessons").get(0).path("blocks");
    }
}
