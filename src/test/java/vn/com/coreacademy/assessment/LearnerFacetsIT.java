package vn.com.coreacademy.assessment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

/** Lĩnh vực, chương trình, phân hệ mà học viên thấy: chỉ giá trị có khóa nhìn thấy được, theo thứ tự sortNo rồi tên. */
class LearnerFacetsIT extends AssessmentIntegrationTest {
    private static final String FACETS = "/api/v1/me/learning/facets";
    private static final String CATALOG = "/api/v1/me/learning/catalog";

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

    /** ID lĩnh vực, chương trình, phân hệ mà bản nháp xuất bản của khóa đang dùng. */
    private record Values(long field, long program, long module) {
    }

    private Values values(Course course) {
        return jdbc.queryForObject("""
            SELECT p.field_id, r.program_id, r.module_id
            FROM learning_hub.course_revisions r
            JOIN learning_hub.training_programs p ON p.program_id = r.program_id
            WHERE r.revision_id = ?
            """, (rows, index) -> new Values(rows.getLong(1), rows.getLong(2), rows.getLong(3)), course.revisionId());
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    @Test
    void listsTheNamesOfTheCoursesTheLearnerCanSee() {
        Course course = fixtures.course(LEARNER);
        Values used = values(course);

        JsonNode facets = api.ok(LEARNER, get(FACETS));

        assertThat(facets.fieldNames()).toIterable()
            .containsExactly("fields", "programs", "modules", "fieldsWithUnclassifiedCourses");
        assertThat(ids(facets.path("fields"))).contains(used.field());
        JsonNode program = item(facets.path("programs"), used.program());
        assertThat(program.path("fieldId").asLong()).isEqualTo(used.field());
        assertThat(program.path("code").asText()).isNotBlank();
        assertThat(program.path("name").asText()).startsWith("Danh mục ");
        JsonNode module = item(facets.path("modules"), used.module());
        assertThat(module.path("fieldId").asLong()).isEqualTo(used.field());
        JsonNode field = item(facets.path("fields"), used.field());
        assertThat(field.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "code", "name");
    }

    @Test
    void namedAndUnclassifiedModulesPartitionTheVisibleCoursesOfAField() {
        Course named = fixtures.course(LEARNER);
        Course unclassified = fixtures.course(LEARNER);
        Values used = values(named);
        jdbc.update("""
            update learning_hub.course_revisions set program_id = ?, module_id = null where revision_id = ?
            """, used.program(), unclassified.revisionId());

        JsonNode facets = api.ok(LEARNER, get(FACETS));
        assertThat(facets.path("fieldsWithUnclassifiedCourses"))
            .anySatisfy(field -> assertThat(field.asLong()).isEqualTo(used.field()));

        String url = CATALOG + "?fieldId=" + used.field();
        long all = api.ok(LEARNER, get(url)).path("totalElements").asLong();
        long namedCount = api.ok(LEARNER, get(url + "&moduleId=" + used.module())).path("totalElements").asLong();
        JsonNode remaining = api.ok(LEARNER, get(url + "&withoutModule=true"));
        assertThat(remaining.path("items").findValuesAsText("courseId")).containsExactly(Long.toString(unclassified.id()));
        assertThat(all).isEqualTo(2).isEqualTo(namedCount + remaining.path("totalElements").asLong());
    }

    @Test
    void leavesOutValuesNoVisibleCourseUses() {
        long orgId = fixtures.orgId();
        long field = catalog("fields", orgId, null, "published");
        long program = catalog("programs", orgId, field, "published");
        long module = catalog("modules", orgId, field, "published");

        JsonNode facets = api.ok(LEARNER, get(FACETS));

        assertThat(ids(facets.path("fields"))).doesNotContain(field);
        assertThat(ids(facets.path("programs"))).doesNotContain(program);
        assertThat(ids(facets.path("modules"))).doesNotContain(module);
    }

    @Test
    void aCourseSharedToTheCallersOrgBringsItsValuesIn() {
        Course course = fixtures.course();
        Values used = values(course);
        long other = jdbc.queryForObject("""
            INSERT INTO learning_hub.orgs(parent_id, org_type, code, name, status, sort_no)
            VALUES (NULL, 'UNIT', ?, 'Đơn vị sở hữu khác', 'active', 1) RETURNING org_id
            """, Long.class, AssessmentFixtures.unique("OWN"));
        jdbc.update("UPDATE learning_hub.courses SET owner_org_id = ? WHERE course_id = ?", other, course.id());

        assertThat(ids(api.ok(LEARNER, get(FACETS)).path("programs"))).as("khóa của đơn vị khác chưa chia sẻ")
            .doesNotContain(used.program());

        jdbc.update("""
            INSERT INTO learning_hub.course_shares(course_id, org_id, include_descendants, can_assign,
                can_self_enroll, status, shared_by, shared_at, created_by, updated_by)
            VALUES (?, ?, false, false, true, 'active', ?, now(), ?, ?)
            """, course.id(), fixtures.orgId(), INSTRUCTOR, INSTRUCTOR, INSTRUCTOR);

        JsonNode facets = api.ok(LEARNER, get(FACETS));
        assertThat(ids(facets.path("fields"))).contains(used.field());
        assertThat(ids(facets.path("programs"))).contains(used.program());
        assertThat(ids(facets.path("modules"))).contains(used.module());
    }

    @Test
    void sortsBySortNoThenName() {
        Values first = values(fixtures.course(LEARNER));
        Values second = values(fixtures.course(LEARNER));
        Values third = values(fixtures.course(LEARNER));
        // Tên có thể trùng thứ tự id nên đặt tên ngược chiều với sortNo để phân biệt hai tiêu chí.
        sort("training_fields", "field_id", first.field(), 90_002, "B");
        sort("training_fields", "field_id", second.field(), 90_001, "Z");
        sort("training_fields", "field_id", third.field(), 90_001, "A");
        sort("training_programs", "program_id", first.program(), 90_002, "B");
        sort("training_programs", "program_id", second.program(), 90_001, "Z");
        sort("training_programs", "program_id", third.program(), 90_001, "A");
        sort("business_modules", "module_id", first.module(), 90_002, "B");
        sort("business_modules", "module_id", second.module(), 90_001, "Z");
        sort("business_modules", "module_id", third.module(), 90_001, "A");

        JsonNode facets = api.ok(LEARNER, get(FACETS));

        assertThat(ordered(facets.path("fields"), Set.of(first.field(), second.field(), third.field())))
            .containsExactly(third.field(), second.field(), first.field());
        assertThat(ordered(facets.path("programs"), Set.of(first.program(), second.program(), third.program())))
            .containsExactly(third.program(), second.program(), first.program());
        assertThat(ordered(facets.path("modules"), Set.of(first.module(), second.module(), third.module())))
            .containsExactly(third.module(), second.module(), first.module());
    }

    private void sort(String table, String idColumn, long id, int sortNo, String name) {
        jdbc.update("UPDATE learning_hub." + table + " SET sort_no = ?, name = ? WHERE " + idColumn + " = ?",
            sortNo, name, id);
    }

    private static List<Long> ordered(JsonNode items, Set<Long> mine) {
        return ids(items).stream().filter(mine::contains).toList();
    }

    private static JsonNode item(JsonNode items, long id) {
        for (JsonNode item : items) {
            if (item.path("id").asLong() == id) {
                return item;
            }
        }
        throw new AssertionError("Không có mục " + id + " trong " + items);
    }

    private long catalog(String kind, long orgId, Long fieldId, String status) {
        String code = AssessmentFixtures.unique("X");
        Map<String, Object> body = new HashMap<>();
        body.put("ownerOrgId", orgId);
        body.put("fieldId", fieldId);
        body.put("code", code);
        body.put("name", "Không dùng " + code);
        body.put("description", null);
        body.put("status", status);
        body.put("sortNo", 0);
        return api.ok(INSTRUCTOR, api.post(body, "/api/v1/training-catalog/" + kind)).path("id").asLong();
    }
}
