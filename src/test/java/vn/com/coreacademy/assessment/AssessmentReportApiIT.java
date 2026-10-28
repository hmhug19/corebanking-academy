package vn.com.coreacademy.assessment;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.REPORT_VIEWER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/**
 * Báo cáo thi trên dữ liệu dựng bằng API: 000004 đạt 50% (đúng câu SINGLE), 000007 được 0%, 000005 được giao nhưng
 * chưa thi. Số liệu kỳ vọng tính tay từ kịch bản này.
 */
class AssessmentReportApiIT extends AssessmentIntegrationTest {
    private static final String URL = "/api/v1/reports/assessment";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private Course course;
    private long quizId;
    private long competencyId;
    private long singleId;
    private long multipleId;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        course = fixtures.course(LEARNER, LEARNER_2, INSTRUCTOR);
        competencyId = fixtures.competency();
        singleId = fixtures.question(course.id(), competencyId, "SINGLE", 0);
        multipleId = fixtures.question(course.id(), competencyId, "MULTIPLE", 0, 2);
        quizId = fixtures.quiz(quizBody(course.id()), List.of(singleId, multipleId), true);

        JsonNode session = api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        for (JsonNode question : session.path("questions")) {
            long firstOption = question.path("options").get(0).path("id").asLong();
            api.ok(LEARNER, api.patch(Map.of("selectedOptionIds", List.of(firstOption)),
                "/api/v1/me/quiz-attempts/{a}/questions/{q}", attemptId, question.path("attemptQuestionId").asLong()));
        }
        api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", attemptId));

        long failed = api.ok(LEARNER_2, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId))
            .path("attemptId").asLong();
        api.ok(LEARNER_2, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", failed));
    }

    @Test
    void overviewAggregatesPerLearnerWithScoreMethod() {
        JsonNode overview = api.ok(ADMIN, get(URL + "/overview?quizId={q}", quizId));
        assertThat(overview.path("gradedAttempts").asLong()).isEqualTo(2);
        assertThat(overview.path("learners").asLong()).isEqualTo(2);
        assertThat(overview.path("passedLearners").asLong()).isEqualTo(1);
        assertThat(overview.path("passRate").decimalValue()).isEqualByComparingTo("50");
        assertThat(overview.path("averageScore").decimalValue()).isEqualByComparingTo("25");
        assertThat(overview.path("inProgressAttempts").asLong()).isZero();
        JsonNode byQuiz = overview.path("byQuiz");
        assertThat(byQuiz).hasSize(1);
        assertThat(byQuiz.get(0).path("id").asLong()).isEqualTo(quizId);
        assertThat(byQuiz.get(0).path("name").asText()).startsWith("Bài kiểm tra");
        JsonNode byOrg = overview.path("byOrg");
        assertThat(byOrg).hasSize(1);
        assertThat(byOrg.get(0).path("name").asText()).isEqualTo("Ban Quản lý và phát triển Core Banking");
        assertThat(byOrg.get(0).path("passRate").decimalValue()).isEqualByComparingTo("50");

        JsonNode future = api.ok(ADMIN, get(URL + "/overview?quizId={q}&from=2099-01-01T00:00:00Z", quizId));
        assertThat(future.path("learners").asLong()).isZero();
        assertThat(future.path("passRate").isNull()).isTrue();
        api.error(ADMIN, get(URL + "/overview?from=2030-01-02T00:00:00Z&to=2030-01-01T00:00:00Z"),
            "CORE-VALIDATION_FAILED");
    }

    @Test
    void resultsCarryNamesAndFilter() {
        JsonNode page = api.ok(ADMIN, get(URL + "/results?quizId={q}&sort=scorePercent,desc", quizId));
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        JsonNode best = page.path("items").get(0);
        assertThat(best.path("userId").asText()).isEqualTo(LEARNER);
        assertThat(best.path("userName").asText()).isEqualTo("Học viên tiền gửi Demo");
        assertThat(best.path("orgName").asText()).isEqualTo("Ban Quản lý và phát triển Core Banking");
        assertThat(best.path("teamName").asText()).isEqualTo("Nhóm BA&QC Tiền gửi");
        assertThat(best.path("scorePercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(best.path("courseId").asLong()).isEqualTo(course.id());

        assertThat(api.ok(ADMIN, get(URL + "/results?quizId={q}&passed=false", quizId)).path("items")
            .findValuesAsText("userId")).containsExactly(LEARNER_2);
        assertThat(api.ok(ADMIN, get(URL + "/results?quizId={q}&userId={u}", quizId, LEARNER)).path("totalElements")
            .asLong()).isEqualTo(1);
        assertThat(api.ok(ADMIN, get(URL + "/results?courseId={c}", course.id())).path("totalElements").asLong())
            .isEqualTo(2);
        api.error(ADMIN, get(URL + "/results?status=DONE"), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, get(URL + "/results?sort=userName,asc"), "CORE-VALIDATION_FAILED");
    }

    @Test
    void learnersIncludeAssignedButNotStarted() {
        JsonNode page = api.ok(ADMIN, get(URL + "/learners?quizId={q}&sort=userId,asc", quizId));
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        Map<String, JsonNode> byUser = new HashMap<>();
        page.path("items").forEach(row -> byUser.put(row.path("userId").asText(), row));
        assertThat(byUser.get(LEARNER).path("learnerStatus").asText()).isEqualTo("PASSED");
        assertThat(byUser.get(LEARNER).path("attemptsUsed").asLong()).isEqualTo(1);
        assertThat(byUser.get(LEARNER).path("attemptsLeft").asLong()).isEqualTo(2);
        assertThat(byUser.get(LEARNER).path("finalScore").decimalValue()).isEqualByComparingTo("50");
        assertThat(byUser.get(LEARNER).path("enrollmentId").isNumber()).isTrue();
        assertThat(byUser.get(LEARNER_2).path("learnerStatus").asText()).isEqualTo("FAILED");
        JsonNode notStarted = byUser.get(INSTRUCTOR);
        assertThat(notStarted.path("learnerStatus").asText()).isEqualTo("NOT_STARTED");
        assertThat(notStarted.path("attemptsUsed").asLong()).isZero();
        assertThat(notStarted.path("finalScore").isNull()).isTrue();
        assertThat(notStarted.path("userName").asText()).isEqualTo("Giảng viên Demo");

        assertThat(api.ok(ADMIN, get(URL + "/learners?quizId={q}&learnerStatus=NOT_STARTED", quizId)).path("items")
            .findValuesAsText("userId")).containsExactly(INSTRUCTOR);
        JsonNode missing = api.error(ADMIN, get(URL + "/learners"), "CORE-VALIDATION_FAILED");
        assertThat(missing.path("errors").get(0).path("field").asText()).isEqualTo("quizId");
        api.error(ADMIN, get(URL + "/learners?quizId={q}&learnerStatus=X", quizId), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, get(URL + "/learners?quizId=999999999"), "ASSESSMENT-NOT_FOUND");
    }

    @Test
    void competencyByUserAndOrg() {
        JsonNode byUser = api.ok(ADMIN, get(URL + "/competency?groupBy=user&quizId={q}&sort=groupKey,asc", quizId));
        assertThat(byUser.path("totalElements").asLong()).isEqualTo(2);
        JsonNode first = byUser.path("items").get(0);
        assertThat(first.path("groupKey").asText()).isEqualTo(LEARNER);
        assertThat(first.path("groupName").asText()).isEqualTo("Học viên tiền gửi Demo");
        assertThat(first.path("competencyId").asLong()).isEqualTo(competencyId);
        assertThat(first.path("totalQuestions").asLong()).isEqualTo(2);
        assertThat(first.path("correctCount").asLong()).isEqualTo(1);
        assertThat(first.path("percent").decimalValue()).isEqualByComparingTo("50");

        JsonNode byOrg = api.ok(ADMIN, get(URL + "/competency?groupBy=org&quizId={q}", quizId));
        assertThat(byOrg.path("totalElements").asLong()).isEqualTo(1);
        assertThat(byOrg.path("items").get(0).path("groupName").asText())
            .isEqualTo("Ban Quản lý và phát triển Core Banking");
        assertThat(byOrg.path("items").get(0).path("totalQuestions").asLong()).isEqualTo(4);
        assertThat(byOrg.path("items").get(0).path("percent").decimalValue()).isEqualByComparingTo("25");
        api.error(ADMIN, get(URL + "/competency?groupBy=team"), "CORE-VALIDATION_FAILED");
    }

    @Test
    void questionAnalysisRanksHardestFirst() {
        JsonNode page = api.ok(ADMIN, get(URL + "/questions?quizId={q}", quizId));
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        JsonNode hardest = page.path("items").get(0);
        assertThat(hardest.path("questionId").asLong()).isEqualTo(multipleId);
        assertThat(hardest.path("wrongPercent").decimalValue()).isEqualByComparingTo("100");
        assertThat(hardest.path("timesServed").asLong()).isEqualTo(2);
        assertThat(hardest.path("timesBlank").asLong()).isEqualTo(1);
        assertThat(hardest.path("code").asText()).isEqualTo("Q" + multipleId);
        assertThat(hardest.path("contentPreview").asText()).isNotBlank();
        JsonNode easier = page.path("items").get(1);
        assertThat(easier.path("questionId").asLong()).isEqualTo(singleId);
        assertThat(easier.path("wrongPercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(easier.path("timesCorrect").asLong()).isEqualTo(1);
    }

    @Test
    void scopeLimitsWhatEachRoleSees() {
        api.http(LEARNER, get(URL + "/overview"), 403, "CORE-ACCESS_DENIED");
        api.http(null, get(URL + "/overview"), 401, "CORE-UNAUTHENTICATED");
        assertThat(api.ok(INSTRUCTOR, get(URL + "/results?quizId={q}", quizId)).path("totalElements").asLong())
            .as("ORG gồm đơn vị con").isEqualTo(2);
        assertThat(api.ok(REPORT_VIEWER, get(URL + "/results?quizId={q}", quizId)).path("totalElements").asLong())
            .as("TEAM Ban Giám đốc không thấy học viên nhóm khác").isZero();
        assertThat(api.ok(REPORT_VIEWER, get(URL + "/learners?quizId={q}", quizId)).path("totalElements").asLong())
            .isZero();

        Long team = jdbc.queryForObject("SELECT team_id FROM learning_hub.teams WHERE code = 'BIDV110B803103'",
            Long.class);
        Long role = jdbc.queryForObject("SELECT role_id FROM learning_hub.roles WHERE code = 'REPORT_VIEWER'",
            Long.class);
        Long org = jdbc.queryForObject("SELECT org_id FROM learning_hub.teams WHERE team_id = ?", Long.class, team);
        jdbc.update("""
            INSERT INTO learning_hub.user_roles(user_id, role_id, scope, org_id, team_id, granted_by)
            VALUES (?, ?, 'TEAM', ?, ?, '000001')
            """, REPORT_VIEWER, role, org, team);
        try {
            JsonNode scoped = api.ok(REPORT_VIEWER, get(URL + "/results?quizId={q}", quizId));
            assertThat(scoped.path("items").findValuesAsText("userId")).containsExactly(LEARNER);
            JsonNode overview = api.ok(REPORT_VIEWER, get(URL + "/overview?quizId={q}", quizId));
            assertThat(overview.path("learners").asLong()).isEqualTo(1);
            assertThat(overview.path("passRate").decimalValue()).isEqualByComparingTo("100");
            assertThat(api.ok(REPORT_VIEWER, get(URL + "/learners?quizId={q}", quizId)).path("items")
                .findValuesAsText("userId")).containsExactly(LEARNER);
        } finally {
            jdbc.update("DELETE FROM learning_hub.user_roles WHERE user_id = ? AND scope = 'TEAM' AND team_id = ?",
                REPORT_VIEWER, team);
        }

        Long otherTeam = jdbc.queryForObject("SELECT team_id FROM learning_hub.teams WHERE code = 'BIDV110B8031280'",
            Long.class);
        assertThat(api.ok(ADMIN, get(URL + "/results?quizId={q}&teamId={t}", quizId, otherTeam)).path("items")
            .findValuesAsText("userId")).as("teamId chỉ thu hẹp").containsExactly(LEARNER_2);
        assertThat(api.ok(ADMIN, get(URL + "/results?quizId={q}&orgId={o}", quizId, course.ownerOrgId()))
            .path("totalElements").asLong()).isEqualTo(2);
    }

    @Test
    void exportsCsvWithSameFilters() {
        MockHttpServletResponse results = api.raw(ADMIN, get(URL + "/results/export?quizId={q}", quizId));
        assertThat(results.getStatus()).isEqualTo(200);
        assertThat(results.getContentType()).startsWith("text/csv");
        assertThat(results.getHeader("Content-Disposition")).contains("assessment-results.csv");
        String text = new String(results.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Mã lượt,Bài kiểm tra,");
        assertThat(text.split("\r\n")).hasSize(3);
        assertThat(text).contains("Học viên tiền gửi Demo");

        List<String> lines = new ArrayList<>();
        for (String type : List.of("learners", "competency", "questions")) {
            MockHttpServletResponse csv = api.raw(ADMIN, get(URL + "/{t}/export?quizId={q}", type, quizId));
            assertThat(csv.getStatus()).as(type).isEqualTo(200);
            lines.add(new String(csv.getContentAsByteArray(), StandardCharsets.UTF_8));
        }
        assertThat(lines.get(0).split("\r\n")).hasSize(4);
        assertThat(lines.get(1).split("\r\n")).hasSize(3);
        assertThat(lines.get(2).split("\r\n")).hasSize(3);

        api.error(ADMIN, get(URL + "/unknown/export").accept("text/csv", "application/json"),
            "CORE-VALIDATION_FAILED");
        api.http(LEARNER, get(URL + "/results/export"), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void myCompetencyShowsOnlyCallerData() {
        JsonNode mine = api.ok(LEARNER, get("/api/v1/me/competency?courseId={c}", course.id()));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).path("competencyId").asLong()).isEqualTo(competencyId);
        assertThat(mine.get(0).path("totalQuestions").asLong()).isEqualTo(2);
        assertThat(mine.get(0).path("correctCount").asLong()).isEqualTo(1);
        assertThat(mine.get(0).path("percent").decimalValue()).isEqualByComparingTo("50");
        assertThat(api.ok(INSTRUCTOR, get("/api/v1/me/competency?courseId={c}", course.id()))).isEmpty();
    }
}
