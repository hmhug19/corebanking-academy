package vn.com.coreacademy.learning;

import java.util.HashMap;
import java.util.List;
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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.unique;

/** Xóa lĩnh vực và chương trình: chỉ khi trống; còn nội dung bên trong thì từ chối để chuyển sang Lưu trữ. */
class CatalogDeleteIT extends AssessmentIntegrationTest {
    private static final String FIELDS = "/api/v1/training-catalog/fields";
    private static final String PROGRAMS = "/api/v1/training-catalog/programs";
    private static final String IN_USE = "LEARNING-CATALOG_IN_USE";

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

    private JsonNode create(String path, Long fieldId) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownerOrgId", org);
        body.put("fieldId", fieldId);
        body.put("code", unique("DEL"));
        body.put("name", "Xóa thử");
        body.put("description", null);
        body.put("status", "draft");
        body.put("sortNo", 0);
        return api.ok(INSTRUCTOR, api.post(body, path));
    }

    private boolean inTree(String level, long id) {
        for (JsonNode field : api.ok(INSTRUCTOR, ApiClient.get("/api/v1/training-catalog/tree?ownerOrgId={o}", org))
            .path("fields")) {
            if ("field".equals(level) && field.path("id").asLong() == id) {
                return true;
            }
            for (JsonNode program : field.path("programs")) {
                if ("program".equals(level) && program.path("id").asLong() == id) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void fieldAndModuleChangesAreAtomicAndVersioned() {
        String fieldCode = unique("AGG");
        Map<String, Object> body = new HashMap<>(Map.of("ownerOrgId", org, "code", fieldCode,
            "name", "Lĩnh vực kèm phân hệ", "status", "draft"));
        body.put("modules", List.of(
            Map.of("code", "M1", "name", "Phân hệ 1", "status", "published"),
            Map.of("code", "M2", "name", "Phân hệ 2", "status", "published")));
        JsonNode field = api.ok(INSTRUCTOR, api.post(body, FIELDS));
        long id = field.path("id").asLong();
        JsonNode rows = api.ok(INSTRUCTOR,
            ApiClient.get("/api/v1/training-catalog/modules?fieldId={id}", id)).path("items");
        assertThat(rows).hasSize(2);

        body.put("name", "Đã sửa lĩnh vực");
        body.put("modules", List.of(
            Map.of("id", rows.get(0).path("id").asLong(), "expectedVersion", 0,
                "code", "M1", "name", "Phân hệ đã sửa", "status", "published"),
            Map.of("id", rows.get(1).path("id").asLong(), "expectedVersion", 0,
                "code", "M2", "name", "Phân hệ 2", "status", "published", "deleted", true)));
        field = api.ok(INSTRUCTOR, api.put(body, FIELDS + "/{id}?expectedVersion={v}", id,
            field.path("rowVersion").asLong()));
        rows = api.ok(INSTRUCTOR,
            ApiClient.get("/api/v1/training-catalog/modules?fieldId={id}", id)).path("items");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("name").asText()).isEqualTo("Phân hệ đã sửa");

        body.put("name", "Không được lưu một phần");
        body.put("modules", List.of(
            Map.of("code", "M3", "name", "Phân hệ 3", "status", "published"),
            Map.of("code", "M1", "name", "Trùng mã", "status", "published")));
        api.error(INSTRUCTOR, api.put(body, FIELDS + "/{id}?expectedVersion={v}", id,
            field.path("rowVersion").asLong()), "LEARNING-DUPLICATE");
        assertThat(jdbc.queryForObject("select name from learning_hub.training_fields where field_id = ?",
            String.class, id)).isEqualTo("Đã sửa lĩnh vực");
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.business_modules where field_id = ?",
            Long.class, id)).isEqualTo(1L);
    }

    @Test
    void anEmptyProgramAndThenItsEmptyFieldAreDeleted() {
        JsonNode field = create(FIELDS, null);
        JsonNode program = create(PROGRAMS, field.path("id").asLong());
        long programId = program.path("id").asLong();
        long fieldId = field.path("id").asLong();

        api.ok(INSTRUCTOR, ApiClient.delete(PROGRAMS + "/{id}?expectedVersion={v}", programId,
            program.path("rowVersion").asLong()));
        assertThat(inTree("program", programId)).isFalse();

        api.ok(INSTRUCTOR, ApiClient.delete(FIELDS + "/{id}?expectedVersion={v}", fieldId,
            field.path("rowVersion").asLong()));
        assertThat(inTree("field", fieldId)).isFalse();
    }

    @Test
    void aFieldWithAProgramAndAProgramWithACourseAreRefused() {
        Course course = fixtures.course();
        long programId = jdbc.queryForObject(
            "select program_id from learning_hub.course_revisions where course_id = ? limit 1", Long.class,
            course.id());
        long fieldId = jdbc.queryForObject("select field_id from learning_hub.training_programs where program_id = ?",
            Long.class, programId);
        long fieldVersion = jdbc.queryForObject("select row_version from learning_hub.training_fields "
            + "where field_id = ?", Long.class, fieldId);
        long programVersion = jdbc.queryForObject("select row_version from learning_hub.training_programs "
            + "where program_id = ?", Long.class, programId);

        api.error(INSTRUCTOR, ApiClient.delete(FIELDS + "/{id}?expectedVersion={v}", fieldId, fieldVersion), IN_USE);
        api.error(INSTRUCTOR, ApiClient.delete(PROGRAMS + "/{id}?expectedVersion={v}", programId, programVersion),
            IN_USE);
        assertThat(inTree("program", programId)).isTrue();
    }

    @Test
    void aProgramHoldingQuestionBankItemsIsRefused() {
        JsonNode field = create(FIELDS, null);
        JsonNode program = create(PROGRAMS, field.path("id").asLong());
        long programId = program.path("id").asLong();
        Course course = fixtures.course();
        long question = fixtures.question(course.id(), fixtures.competency(), "SINGLE", 0);
        jdbc.update("update learning_hub.questions set program_id = ? where question_id = ?", programId, question);

        api.error(INSTRUCTOR, ApiClient.delete(PROGRAMS + "/{id}?expectedVersion={v}", programId,
            program.path("rowVersion").asLong()), IN_USE);
        assertThat(inTree("program", programId)).isTrue();
    }

    @Test
    void aStaleVersionADifferentRoleAndAnUnknownIdAreRefused() {
        JsonNode field = create(FIELDS, null);
        long id = field.path("id").asLong();
        long version = field.path("rowVersion").asLong();

        api.error(INSTRUCTOR, ApiClient.delete(FIELDS + "/{id}?expectedVersion={v}", id, version + 5),
            "LEARNING-CONFLICT");
        api.http(LEARNER, ApiClient.delete(FIELDS + "/{id}?expectedVersion={v}", id, version), 403,
            "CORE-ACCESS_DENIED");
        api.error(INSTRUCTOR, ApiClient.delete(PROGRAMS + "/{id}?expectedVersion=0", 999_999_999L),
            "LEARNING-NOT_FOUND");
        assertThat(inTree("field", id)).isTrue();
    }

    @Test
    void deletingModulesChecksUsageVersionAndManagementPermission() {
        Course course = fixtures.course();
        long moduleId = jdbc.queryForObject(
            "select module_id from learning_hub.course_revisions where revision_id = ?", Long.class,
            course.revisionId());
        long version = jdbc.queryForObject(
            "select row_version from learning_hub.business_modules where module_id = ?", Long.class, moduleId);
        String path = "/api/v1/training-catalog/modules/{id}?expectedVersion={v}";
        api.error(INSTRUCTOR, ApiClient.delete(path, moduleId, version), IN_USE);
        api.error(INSTRUCTOR, ApiClient.delete(path, moduleId, version + 1), "LEARNING-CONFLICT");
        api.http(LEARNER, ApiClient.delete(path, moduleId, version), 403, "CORE-ACCESS_DENIED");

        JsonNode field = create(FIELDS, null);
        JsonNode module = create("/api/v1/training-catalog/modules", field.path("id").asLong());
        api.ok(INSTRUCTOR, ApiClient.delete(path, module.path("id").asLong(), module.path("rowVersion").asLong()));
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.business_modules where module_id = ?",
            Long.class, module.path("id").asLong())).isZero();
    }
}
