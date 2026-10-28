package vn.com.coreacademy.assessment;

import java.util.HashMap;
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
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.delete;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.unique;

class CompetencyApiIT extends AssessmentIntegrationTest {
    private static final String URL = "/api/v1/competencies";

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
    void globalAdminCreatesAndEveryoneCanList() {
        Map<String, Object> body = body(unique("COMP"), null);
        JsonNode created = api.ok(ADMIN, api.post(body, URL));

        assertThat(created.path("id").isNumber()).isTrue();
        assertThat(created.path("code").asText()).isEqualTo(body.get("code"));
        assertThat(created.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.path("rowVersion").asLong()).isZero();
        assertThat(created.path("createTime").isTextual()).isTrue();
        assertThat(created.has("deleted")).as("không lộ cờ nội bộ").isFalse();

        JsonNode list = api.ok(LEARNER, get(URL));
        assertThat(list.isArray()).isTrue();
        assertThat(list.findValuesAsText("code")).contains((String) body.get("code"));
        JsonNode inactive = api.ok(LEARNER, get(URL + "?status=INACTIVE"));
        assertThat(inactive.findValuesAsText("code")).doesNotContain((String) body.get("code"));
    }

    @Test
    void instructorCannotWriteSharedMasterData() {
        api.http(INSTRUCTOR, api.post(body(unique("COMP"), null), URL), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void unauthenticatedIsRejected() {
        api.http(null, get(URL), 401, "CORE-UNAUTHENTICATED");
    }

    @Test
    void validationReportsFieldPath() {
        Map<String, Object> body = body(unique("COMP"), null);
        body.put("name", "");
        body.put("status", "UNKNOWN");
        JsonNode error = api.error(ADMIN, api.post(body, URL), "CORE-VALIDATION_FAILED");
        assertThat(error.path("errors").findValuesAsText("field")).contains("name", "status");
    }

    @Test
    void invalidStatusFilterIsValidationError() {
        JsonNode error = api.error(LEARNER, get(URL + "?status=X"), "CORE-VALIDATION_FAILED");
        assertThat(error.path("errors").get(0).path("field").asText()).isEqualTo("status");
    }

    @Test
    void duplicateCodeIsReportedOnCodeField() {
        String code = unique("COMP");
        api.ok(ADMIN, api.post(body(code, null), URL));
        JsonNode error = api.error(ADMIN, api.post(body(code, null), URL), "ASSESSMENT-DUPLICATE");
        assertThat(error.path("errors").get(0).path("field").asText()).isEqualTo("code");
        assertThat(error.path("errors").get(0).path("code").asText()).isEqualTo("ALREADY_EXISTS");
    }

    @Test
    void updateRequiresCurrentVersionAndRejectsCycles() {
        JsonNode parent = api.ok(ADMIN, api.post(body(unique("COMP"), null), URL));
        long parentId = parent.path("id").asLong();
        JsonNode child = api.ok(ADMIN, api.post(body(unique("COMP"), parentId), URL));
        long childId = child.path("id").asLong();

        Map<String, Object> rename = body(parent.path("code").asText(), null);
        rename.put("name", "Tên mới");
        api.error(ADMIN, api.put(rename, URL + "/{id}?expectedVersion=99", parentId), "ASSESSMENT-CONFLICT");
        JsonNode updated = api.ok(ADMIN, api.put(rename, URL + "/{id}?expectedVersion=0", parentId));
        assertThat(updated.path("name").asText()).isEqualTo("Tên mới");
        assertThat(updated.path("rowVersion").asLong()).isEqualTo(1);

        Map<String, Object> cycle = body(parent.path("code").asText(), childId);
        JsonNode error = api.error(ADMIN, api.put(cycle, URL + "/{id}?expectedVersion=1", parentId),
            "ASSESSMENT-INVALID_REFERENCE");
        assertThat(error.path("errors").get(0).path("field").asText()).isEqualTo("parentId");
    }

    @Test
    void deleteIsBlockedWhileUsedThenSoftDeletes() {
        long used = fixtures.competency();
        AssessmentFixtures.Course course = fixtures.course();
        fixtures.question(course.id(), used, "SINGLE", 0);
        api.error(ADMIN, delete(URL + "/{id}?expectedVersion=0", used), "ASSESSMENT-COMPETENCY_IN_USE");

        long free = fixtures.competency();
        api.error(ADMIN, delete(URL + "/{id}?expectedVersion=5", free), "ASSESSMENT-CONFLICT");
        JsonNode deleted = api.ok(ADMIN, delete(URL + "/{id}?expectedVersion=0", free));
        assertThat(deleted.isNull()).isTrue();
        assertThat(api.ok(ADMIN, get(URL)).findValuesAsText("id")).doesNotContain(String.valueOf(free));
        api.error(ADMIN, delete(URL + "/{id}?expectedVersion=1", free), "ASSESSMENT-NOT_FOUND");
    }

    private static Map<String, Object> body(String code, Long parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Năng lực " + code);
        body.put("description", "Mô tả");
        body.put("parentId", parentId);
        body.put("sortNo", 1);
        body.put("status", "ACTIVE");
        return body;
    }
}
