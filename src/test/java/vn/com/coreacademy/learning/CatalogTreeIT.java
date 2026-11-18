package vn.com.coreacademy.learning;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.unique;

/** Cây Lĩnh vực → Chương trình → Khóa học của một đơn vị, cắt theo quyền của người gọi. */
class CatalogTreeIT extends AssessmentIntegrationTest {
    private static final String TREE = "/api/v1/training-catalog/tree";

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

    private JsonNode tree(String user, long ownerOrg) {
        return api.ok(user, ApiClient.get(TREE + "?ownerOrgId={org}", ownerOrg)).path("fields");
    }

    private Map<String, Object> item(Long fieldId, String code, String status) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownerOrgId", org);
        body.put("fieldId", fieldId);
        body.put("code", code);
        body.put("name", "Cây " + code);
        body.put("description", "Mô tả " + code);
        body.put("status", status);
        body.put("sortNo", 3);
        return body;
    }

    private JsonNode byId(JsonNode nodes, long id) {
        for (JsonNode node : nodes) {
            if (node.path("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    @Test
    void listsFieldsProgramsAndCoursesWithEverythingAnUpdateNeeds() {
        Course course = fixtures.course();
        JsonNode field = null;
        JsonNode program = null;
        JsonNode node = null;
        for (JsonNode candidate : tree(INSTRUCTOR, org)) {
            for (JsonNode p : candidate.path("programs")) {
                if (byId(p.path("courses"), course.id()) != null) {
                    field = candidate;
                    program = p;
                    node = byId(p.path("courses"), course.id());
                }
            }
        }
        assertThat(node).as("khóa nằm dưới chương trình của nó").isNotNull();
        assertThat(program.path("fieldId").asLong()).isEqualTo(field.path("id").asLong());
        assertThat(field.path("ownerOrgId").asLong()).isEqualTo(org);
        for (String name : new String[] {"code", "name", "description", "status", "sortNo", "rowVersion"}) {
            assertThat(field.has(name)).as("lĩnh vực có " + name).isTrue();
            assertThat(program.has(name)).as("chương trình có " + name).isTrue();
        }
        assertThat(node.path("code").asText()).isNotBlank();
        assertThat(node.path("title").asText()).isEqualTo("Khóa kiểm thử Quiz");
        assertThat(node.path("lifecycleStatus").asText()).isEqualTo("active");
        assertThat(node.path("published").asBoolean()).isTrue();
    }

    @Test
    void emptyFieldsAndProgramsAreListedWithEmptyChildrenAndOrderFollowsCreationTime() {
        long emptyField = api.ok(INSTRUCTOR, api.post(item(null, unique("TF"), "draft"),
            "/api/v1/training-catalog/fields")).path("id").asLong();
        long programOfEmpty = api.ok(INSTRUCTOR, api.post(item(emptyField, unique("TP"), "paused"),
            "/api/v1/training-catalog/programs")).path("id").asLong();

        JsonNode fields = tree(INSTRUCTOR, org);
        JsonNode field = byId(fields, emptyField);
        assertThat(field).isNotNull();
        assertThat(field.path("status").asText()).isEqualTo("draft");
        JsonNode program = byId(field.path("programs"), programOfEmpty);
        assertThat(program).isNotNull();
        assertThat(program.path("status").asText()).isEqualTo("paused");
        assertThat(program.path("courses")).isEmpty();

        List<Long> actual = new ArrayList<>();
        for (JsonNode f : fields) {
            actual.add(f.path("id").asLong());
        }
        assertThat(actual).containsExactlyElementsOf(jdbc.queryForList(
            "select field_id from learning_hub.training_fields where owner_org_id = ? order by create_time, field_id",
            Long.class, org));
    }

    @Test
    void aCourseWithoutAProgramIsNotInTheTree() {
        Map<String, Object> create = new HashMap<>();
        create.put("ownerOrgId", org);
        create.put("ownerTeamId", null);
        create.put("code", unique("NP"));
        create.put("title", "Khóa chưa xếp chương trình");
        long id = api.ok(INSTRUCTOR, api.post(create, "/api/v1/courses")).path("course").path("id").asLong();

        for (JsonNode field : tree(INSTRUCTOR, org)) {
            for (JsonNode program : field.path("programs")) {
                assertThat(byId(program.path("courses"), id)).isNull();
            }
        }
    }

    @Test
    void ownInstructorReceivesOnlyTheirCatalogAndDashboardCounts() {
        Course own = fixtures.course(LEARNER);
        Course other = fixtures.course(LEARNER);
        long ownProgram = program(own);
        long otherProgram = program(other);
        jdbc.update("update learning_hub.courses set owner_user_id = ? where course_id = ?", LEARNER_2, own.id());
        jdbc.update("""
            update learning_hub.course_enrollments
            set started_at = now(), completed_at = now(), status = 'completed', progress_percent = 100
            where course_id in (?, ?)
            """, own.id(), other.id());

        long grant = jdbc.queryForObject("""
            insert into learning_hub.user_roles(user_id, role_id, scope)
            select ?, role_id, 'OWN' from learning_hub.roles where code = 'INSTRUCTOR'
            returning ur_id
            """, Long.class, LEARNER_2);
        try {
            JsonNode programs = api.ok(LEARNER_2, ApiClient.get(
                "/api/v1/training-catalog/programs?ownerOrgId={org}", org)).path("items");
            assertThat(byId(programs, ownProgram)).isNotNull();
            assertThat(byId(programs, otherProgram)).isNull();
            assertThat(tree(LEARNER_2, org)).hasSize(1);
            api.http(LEARNER_2, ApiClient.get("/api/v1/courses/{id}", other.id()), 403, "CORE-ACCESS_DENIED");

            String dashboard = "/api/v1/reports/learning/dashboard";
            JsonNode summary = api.ok(LEARNER_2, ApiClient.get(dashboard));
            assertThat(summary.path("counts").path("enrollments").asLong()).isEqualTo(1);
            assertThat(summary.path("counts").path("completed").asLong()).isEqualTo(1);
            JsonNode daily = summary.path("daily");
            assertThat(daily).hasSize(14);
            assertThat(daily.get(13).path("started").asLong()).isEqualTo(1);
            assertThat(daily.get(13).path("completed").asLong()).isEqualTo(1);
            JsonNode leaders = summary.path("topLearners");
            assertThat(leaders).hasSize(1);
            assertThat(leaders.get(0).path("userId").asText()).isEqualTo(LEARNER);
            assertThat(leaders.get(0).path("fullName").asText()).isNotBlank();
            assertThat(leaders.get(0).path("completed").asLong()).isEqualTo(1);

            jdbc.update("""
                update learning_hub.course_enrollments
                set started_at = now() - interval '9 days', completed_at = now() - interval '9 days'
                where course_id = ?
                """, own.id());
            JsonNode week = api.ok(LEARNER_2, ApiClient.get(dashboard + "?days=7"));
            assertThat(week.path("daily")).hasSize(7);
            assertThat(week.path("topLearners")).isEmpty();
            assertThat(week.path("counts").path("completed").asLong()).isEqualTo(1);
            JsonNode month = api.ok(LEARNER_2, ApiClient.get(dashboard + "?days=30"));
            assertThat(month.path("daily")).hasSize(30);
            assertThat(month.path("topLearners").get(0).path("completed").asLong()).isEqualTo(1);
            api.error(LEARNER_2, ApiClient.get(dashboard + "?days=3"), "CORE-VALIDATION_FAILED");
            api.http(LEARNER_2, ApiClient.get("/api/v1/reports/learning/summary"), 403, "CORE-ACCESS_DENIED");
            api.http(LEARNER, ApiClient.get(dashboard), 403, "CORE-ACCESS_DENIED");
        } finally {
            jdbc.update("delete from learning_hub.user_roles where ur_id = ?", grant);
        }
    }

    @Test
    void teamInstructorReceivesProgramsOnlyForCoursesOwnedByTheirTeam() {
        long team = jdbc.queryForObject("select team_id from learning_hub.users where user_id = ?",
            Long.class, LEARNER_2);
        Course teamCourse = fixtures.course();
        Course unitCourse = fixtures.course();
        jdbc.update("update learning_hub.courses set owner_team_id = ? where course_id = ?", team, teamCourse.id());
        long grant = jdbc.queryForObject("""
            insert into learning_hub.user_roles(user_id, role_id, scope, org_id, team_id)
            select ?, role_id, 'TEAM', ?, ? from learning_hub.roles where code = 'INSTRUCTOR'
            returning ur_id
            """, Long.class, LEARNER_2, org, team);
        try {
            JsonNode programs = api.ok(LEARNER_2, ApiClient.get(
                "/api/v1/training-catalog/programs?ownerOrgId={org}", org)).path("items");
            assertThat(programs).hasSize(1);
            assertThat(byId(programs, program(teamCourse))).isNotNull();
            assertThat(byId(programs, program(unitCourse))).isNull();
            api.ok(LEARNER_2, ApiClient.get("/api/v1/courses/{id}", teamCourse.id()));
            api.http(LEARNER_2, ApiClient.get("/api/v1/courses/{id}", unitCourse.id()), 403, "CORE-ACCESS_DENIED");
        } finally {
            jdbc.update("delete from learning_hub.user_roles where ur_id = ?", grant);
        }
    }

    private long program(Course course) {
        return jdbc.queryForObject("select program_id from learning_hub.course_revisions where revision_id = ?",
            Long.class, course.revisionId());
    }

    @Test
    void anotherUnitAndALearnerAreRefused() {
        jdbc.update("""
            insert into learning_hub.orgs(parent_id, org_type, code, name, status, sort_no)
            values (null, 'UNIT', ?, 'Synthetic tree unit', 'active', 99)
            """, "TREE-" + UUID.randomUUID().toString().substring(0, 8));
        long other = jdbc.queryForObject("select max(org_id) from learning_hub.orgs", Long.class);

        api.http(INSTRUCTOR, ApiClient.get(TREE + "?ownerOrgId={org}", other), 403, "CORE-ACCESS_DENIED");
        api.http(LEARNER, ApiClient.get(TREE + "?ownerOrgId={org}", org), 403, "CORE-ACCESS_DENIED");
    }
}
