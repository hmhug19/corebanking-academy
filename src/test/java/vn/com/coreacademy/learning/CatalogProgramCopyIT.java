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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;

/** Sao chép chương trình: bản nháp cùng lĩnh vực, kèm bản nháp của mọi khóa học, không kèm người học. */
class CatalogProgramCopyIT extends AssessmentIntegrationTest {
    private static final String COPY = "/api/v1/training-catalog/programs/{id}/copy";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private long org;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        org = fixtures.orgId();
    }

    private JsonNode programOf(Course course) {
        for (JsonNode field : api.ok(INSTRUCTOR, ApiClient.get("/api/v1/training-catalog/tree?ownerOrgId={o}", org))
            .path("fields")) {
            for (JsonNode program : field.path("programs")) {
                for (JsonNode node : program.path("courses")) {
                    if (node.path("id").asLong() == course.id()) {
                        return program;
                    }
                }
            }
        }
        throw new AssertionError("chương trình của khóa không có trong cây");
    }

    private JsonNode programById(long id) {
        for (JsonNode field : api.ok(INSTRUCTOR, ApiClient.get("/api/v1/training-catalog/tree?ownerOrgId={o}", org))
            .path("fields")) {
            for (JsonNode program : field.path("programs")) {
                if (program.path("id").asLong() == id) {
                    return program;
                }
            }
        }
        throw new AssertionError("không thấy chương trình " + id);
    }

    @Test
    void copiesTheProgramAndItsCoursesAsDrafts() {
        Course course = fixtures.course(LEARNER);
        JsonNode source = programOf(course);
        long sourceId = source.path("id").asLong();

        JsonNode copy = api.ok(INSTRUCTOR, api.post(null, COPY, sourceId));

        assertThat(copy.path("id").asLong()).isNotEqualTo(sourceId);
        assertThat(copy.path("fieldId").asLong()).isEqualTo(source.path("fieldId").asLong());
        assertThat(copy.path("code").asText()).isEqualTo(source.path("code").asText() + "-COPY");
        assertThat(copy.path("name").asText()).isEqualTo(source.path("name").asText() + " (bản sao)");
        assertThat(copy.path("status").asText()).isEqualTo("draft");
        assertThat(copy.path("copiedFromProgramId").asLong()).isEqualTo(sourceId);

        JsonNode copied = programById(copy.path("id").asLong());
        assertThat(copied.path("courses")).hasSize(1);
        JsonNode courseCopy = copied.path("courses").get(0);
        assertThat(courseCopy.path("id").asLong()).isNotEqualTo(course.id());
        assertThat(courseCopy.path("code").asText()).endsWith("-COPY");
        assertThat(courseCopy.path("published").asBoolean()).isFalse();
        assertThat(courseCopy.path("lifecycleStatus").asText()).isEqualTo("active");

        // Nguồn không đổi; không có học viên nào được giao vào khóa mới.
        assertThat(programById(sourceId).path("courses")).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.course_enrollments where course_id = ?",
            Long.class, courseCopy.path("id").asLong())).isZero();
    }

    @Test
    void aSecondCopyGetsANumberedCode() {
        Course course = fixtures.course();
        long sourceId = programOf(course).path("id").asLong();
        String code = programOf(course).path("code").asText();

        api.ok(INSTRUCTOR, api.post(null, COPY, sourceId));
        JsonNode second = api.ok(INSTRUCTOR, api.post(null, COPY, sourceId));

        assertThat(second.path("code").asText()).isEqualTo(code + "-COPY2");
    }

    @Test
    void aLearnerAndAnUnknownProgramAreRefused() {
        long sourceId = programOf(fixtures.course()).path("id").asLong();

        api.http(LEARNER, api.post(null, COPY, sourceId), 403, "CORE-ACCESS_DENIED");
        api.error(INSTRUCTOR, api.post(null, COPY, 999_999_999L), "LEARNING-NOT_FOUND");
    }
}
