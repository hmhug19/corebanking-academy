package vn.com.coreacademy.assessment;

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
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/** counts.assigned và counts.mandatory của progress/summary bằng tổng của danh sách khóa của tôi cùng bộ lọc. */
class ProgressSummaryCountsIT extends AssessmentIntegrationTest {
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
    void assignedAndMandatoryEqualTheListTotals() {
        fixtures.course(LEARNER);
        fixtures.course(LEARNER, LEARNER_2);
        fixtures.course(LEARNER_2);

        JsonNode counts = api.ok(LEARNER, get(LEARNING + "/progress/summary")).path("counts");

        assertThat(counts.fieldNames()).toIterable().containsExactly("total", "completed", "inProgress",
            "notStarted", "overdue", "assigned", "mandatory");
        assertThat(counts.path("assigned").asLong()).isEqualTo(total("/courses?size=1&assigned=true"))
            .isGreaterThanOrEqualTo(2);
        assertThat(counts.path("mandatory").asLong()).isEqualTo(total("/courses?size=1&requirement=mandatory"))
            .isGreaterThanOrEqualTo(2);
        assertThat(counts.path("assigned").asLong()).isLessThanOrEqualTo(counts.path("total").asLong());
        assertThat(counts.path("total").asLong()).isEqualTo(total("/courses?size=1"));
    }

    @Test
    void eachLearnerGetsTheirOwnCounts() {
        fixtures.course(LEARNER_2);

        JsonNode counts = api.ok(LEARNER_2, get(LEARNING + "/progress/summary")).path("counts");

        assertThat(counts.path("assigned").asLong()).isEqualTo(
            api.ok(LEARNER_2, get(LEARNING + "/courses?size=1&assigned=true")).path("totalElements").asLong());
        assertThat(counts.path("mandatory").asLong()).isEqualTo(
            api.ok(LEARNER_2, get(LEARNING + "/courses?size=1&requirement=mandatory")).path("totalElements")
                .asLong());
    }

    private long total(String path) {
        return api.ok(LEARNER, get(LEARNING + path)).path("totalElements").asLong();
    }
}
