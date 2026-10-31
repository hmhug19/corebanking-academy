package vn.com.coreacademy.assessment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/**
 * Bộ lọc assigned, failed, overdue của hai danh sách học viên và của CSV, trên bốn khóa:
 * giao còn hạn, quá hạn, chỉ có trong danh mục, và giao nhưng thi cuối khóa chưa đạt.
 */
class CatalogFilterIT extends AssessmentIntegrationTest {
    private static final String LEARNING = "/api/v1/me/learning";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private Course assigned;
    private Course overdue;
    private Course catalogOnly;
    private Course failed;
    private Set<Long> mine;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        long competency = fixtures.competency();
        assigned = fixtures.course(LEARNER);
        overdue = fixtures.course(LEARNER);
        jdbc.update("""
            UPDATE learning_hub.assignment_recipients ar SET effective_due_at = '2021-01-01T00:00:00Z'
            FROM learning_hub.course_assignments a
            WHERE a.assignment_id = ar.assignment_id AND a.course_id = ?
            """, overdue.id());
        catalogOnly = fixtures.course();
        failed = fixtures.course(LEARNER);
        long question = fixtures.question(failed.id(), competency, "SINGLE", 0);
        Map<String, Object> body = quizBody(failed.id());
        body.put("quizType", "FINAL_EXAM");
        body.put("questionCount", 1);
        long exam = fixtures.quiz(body, List.of(question), true);
        long attemptId = api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", exam))
            .path("attemptId").asLong();
        api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", attemptId));
        mine = Set.of(assigned.id(), overdue.id(), catalogOnly.id(), failed.id());
    }

    @Test
    void assignedKeepsOnlyCoursesWithAnActiveAssignment() {
        assertThat(ids("/courses", "assigned=true")).containsExactlyInAnyOrder(assigned.id(), overdue.id(),
            failed.id());
        assertThat(ids("/catalog", "assigned=true")).containsExactlyInAnyOrder(assigned.id(), overdue.id(),
            failed.id());
        assertThat(ids("/catalog", "assigned=false")).containsExactly(catalogOnly.id());
        assertThat(ids("/courses", "assigned=false")).isEmpty();
    }

    @Test
    void failedKeepsOnlyCoursesWhoseFinalExamWasNeverPassed() {
        for (String list : List.of("/courses", "/catalog")) {
            assertThat(ids(list, "failed=true")).containsExactly(failed.id());
            assertThat(ids(list, "failed=false")).doesNotContain(failed.id()).contains(assigned.id(), overdue.id());
        }
    }

    @Test
    void overdueWorksOnBothLists() {
        for (String list : List.of("/courses", "/catalog")) {
            assertThat(ids(list, "overdue=true")).containsExactly(overdue.id());
            assertThat(ids(list, "overdue=false")).doesNotContain(overdue.id()).contains(assigned.id(),
                failed.id());
        }
        assertThat(ids("/catalog", "overdue=false")).contains(catalogOnly.id());
    }

    @Test
    void filtersCombineWithEachOtherAndWithLearningStatus() {
        assertThat(ids("/catalog", "failed=true&assigned=true")).containsExactly(failed.id());
        assertThat(ids("/catalog", "failed=true&assigned=false")).isEmpty();
        assertThat(ids("/courses", "assigned=true&overdue=true")).containsExactly(overdue.id());
        assertThat(ids("/courses", "failed=true&learningStatus=not_started")).containsExactly(failed.id());
        assertThat(ids("/courses", "failed=true&learningStatus=completed")).isEmpty();
        assertThat(ids("/catalog", "assigned=true&learningStatus=not_started")).containsExactlyInAnyOrder(
            assigned.id(), overdue.id(), failed.id());
        assertThat(ids("/catalog", "assigned=false&learningStatus=not_started")).containsExactly(catalogOnly.id());
    }

    @Test
    void theTotalsFollowTheFilters() {
        JsonNode page = api.ok(LEARNER, get(LEARNING + "/catalog?failed=true&assigned=true&size=1"));

        assertThat(page.path("totalElements").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(page.path("totalElements").asLong()).isEqualTo(
            api.ok(LEARNER, get(LEARNING + "/courses?failed=true&size=1")).path("totalElements").asLong());
    }

    @Test
    void theExportTakesTheSameFiltersAsTheList() throws Exception {
        for (String filter : List.of("failed=true", "assigned=true", "overdue=true", "failed=false")) {
            Set<String> listed = new TreeSet<>();
            for (JsonNode row : api.ok(LEARNER, get(LEARNING + "/courses?size=100&" + filter)).path("items")) {
                listed.add(row.path("code").asText());
            }
            String csv = api.raw(LEARNER, get(LEARNING + "/progress/export?" + filter))
                .getContentAsString(StandardCharsets.UTF_8);
            Set<String> exported = new TreeSet<>();
            csv.lines().skip(1).forEach(line -> exported.add(line.split(",")[0].replace("\"", "")));

            assertThat(exported).as("export " + filter).isEqualTo(listed);
        }
        assertThat(api.raw(LEARNER, get(LEARNING + "/progress/export?failed=true"))
            .getContentAsString(StandardCharsets.UTF_8)).contains(code(failed)).doesNotContain(code(assigned));
    }

    private String code(Course course) {
        return jdbc.queryForObject("SELECT code FROM learning_hub.courses WHERE course_id = ?", String.class,
            course.id());
    }

    /** ID các khóa kiểm thử của lớp này trong kết quả; khóa của lớp IT khác dùng chung cơ sở dữ liệu bị bỏ qua. */
    private List<Long> ids(String list, String query) {
        List<Long> found = new ArrayList<>();
        for (JsonNode row : api.ok(LEARNER, get(LEARNING + list + "?size=100&sort=id,desc&" + query)).path("items")) {
            if (mine.contains(row.path("courseId").asLong())) {
                found.add(row.path("courseId").asLong());
            }
        }
        return found;
    }
}
