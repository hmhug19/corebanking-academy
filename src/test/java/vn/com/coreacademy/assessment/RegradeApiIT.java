package vn.com.coreacademy.assessment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.questionBody;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/**
 * Chấm lại sau khi sửa đáp án câu 1 từ A sang B: 000004 (chọn B, đang 50%) tăng lên 100% và được áp dụng; 000007
 * (chọn A, đang 100%) giảm còn 50% nên chỉ được báo; 000005 đang làm dở không bị đụng tới.
 */
class RegradeApiIT extends AssessmentIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;

    private record Scenario(Course course, long competency, long quizId, long first, long second, long improved,
                            long lowered, long running) {
    }

    private Scenario scenario() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        Course course = fixtures.course(LEARNER, LEARNER_2, INSTRUCTOR);
        long competency = fixtures.competency();
        long first = fixtures.question(course.id(), competency, "SINGLE", 0);
        long second = fixtures.question(course.id(), competency, "SINGLE", 0);
        Map<String, Object> body = quizBody(course.id());
        body.put("passPercent", 100);
        long quizId = fixtures.quiz(body, List.of(first, second), true);
        long improved = attempt(LEARNER, quizId, 1, 0);
        long lowered = attempt(LEARNER_2, quizId, 0, 0);
        long running = api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId))
            .path("attemptId").asLong();
        moveKey(first, 1);
        return new Scenario(course, competency, quizId, first, second, improved, lowered, running);
    }

    @Test
    void dryRunPreviewsThenOnlyBeneficialChangesAreApplied() {
        Scenario s = scenario();
        String url = "/api/v1/quizzes/{id}/regrade";

        JsonNode preview = api.ok(INSTRUCTOR, api.post(request(s.first(), null), url, s.quizId()));
        assertThat(preview.path("dryRun").asBoolean()).isTrue();
        assertThat(preview.path("batchId").isNull()).isTrue();
        assertThat(preview.path("gradedAttempts").asInt()).as("lượt đang làm không tính").isEqualTo(2);
        assertThat(preview.path("increased").asInt()).isEqualTo(1);
        assertThat(preview.path("decreased").asInt()).isEqualTo(1);
        assertThat(preview.path("applied").asInt()).isZero();
        assertThat(preview.path("newCorrectOptionIds")).hasSize(1);
        Map<Long, JsonNode> items = byAttempt(preview);
        JsonNode up = items.get(s.improved());
        assertThat(up.path("outcome").asText()).isEqualTo("INCREASED");
        assertThat(up.path("oldScorePercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(up.path("newScorePercent").decimalValue()).isEqualByComparingTo("100");
        assertThat(up.path("oldPassed").asBoolean()).isFalse();
        assertThat(up.path("newPassed").asBoolean()).isTrue();
        assertThat(up.path("userName").asText()).isEqualTo("Học viên tiền gửi Demo");
        assertThat(up.path("applied").asBoolean()).isFalse();
        JsonNode down = items.get(s.lowered());
        assertThat(down.path("outcome").asText()).isEqualTo("DECREASED");
        assertThat(down.path("newScorePercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(score(s.improved())).as("dry-run không ghi").isEqualByComparingTo("50");

        JsonNode applied = api.ok(INSTRUCTOR, api.post(request(s.first(), false), url, s.quizId()));
        assertThat(applied.path("dryRun").asBoolean()).isFalse();
        assertThat(applied.path("batchId").asText()).hasSize(36);
        assertThat(applied.path("applied").asInt()).isEqualTo(1);
        assertThat(byAttempt(applied).get(s.improved()).path("applied").asBoolean()).isTrue();
        assertThat(byAttempt(applied).get(s.lowered()).path("applied").asBoolean()).isFalse();
        assertThat(score(s.improved())).isEqualByComparingTo("100");
        assertThat(score(s.lowered())).as("không hạ điểm học viên").isEqualByComparingTo("100");

        JsonNode result = api.ok(LEARNER, get("/api/v1/me/quiz-attempts/{id}/result", s.improved()));
        assertThat(result.path("passed").asBoolean()).isTrue();
        assertThat(result.path("correctCount").asInt()).isEqualTo(2);
        JsonNode reviewed = result.path("review").get(0);
        assertThat(reviewed.path("correct").asBoolean()).isTrue();
        assertThat(reviewed.path("options").get(1).path("correct").asBoolean()).as("đáp án mới hiển thị khi xem lại")
            .isTrue();
        assertThat(api.ok(LEARNER, get("/api/v1/me/quizzes/{id}", s.quizId())).path("blockReason").asText())
            .isEqualTo("PASSED");

        JsonNode detail = api.ok(INSTRUCTOR, get("/api/v1/quiz-attempts/{id}", s.improved()));
        JsonNode history = detail.path("regrades");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("batchId").asText()).isEqualTo(applied.path("batchId").asText());
        assertThat(history.get(0).path("oldScorePercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(history.get(0).path("newPassed").asBoolean()).isTrue();
        assertThat(history.get(0).path("regradedBy").asText()).isEqualTo(INSTRUCTOR);
        assertThat(history.get(0).path("reason").asText()).isEqualTo("Sửa đáp án sai");
        assertThat(history.get(0).path("oldCorrectOptionIds")).isNotEqualTo(history.get(0)
            .path("newCorrectOptionIds"));
        assertThat(api.ok(INSTRUCTOR, get("/api/v1/quiz-attempts/{id}", s.lowered())).path("regrades")).isEmpty();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM learning_hub.audit_log WHERE action = 'ASSESSMENT_ATTEMPTS_REGRADED' AND entity_id = ?
            """, Integer.class, String.valueOf(s.quizId()))).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
            SELECT status FROM learning_hub.quiz_attempts WHERE attempt_id = ?
            """, String.class, s.running())).isEqualTo("IN_PROGRESS");
        assertThat(api.ok(INSTRUCTOR, get("/api/v1/quiz-attempts/{id}", s.running())).path("regrades")).isEmpty();

        JsonNode again = api.ok(INSTRUCTOR, api.post(request(s.first(), false), url, s.quizId()));
        assertThat(byAttempt(again).get(s.improved()).path("outcome").asText()).isEqualTo("UNCHANGED");
        assertThat(again.path("applied").asInt()).as("chạy lại không ghi trùng").isZero();
        assertThat(again.path("batchId").isNull()).isTrue();
    }

    @Test
    void replacedOptionsAreSkippedNotGuessed() {
        Scenario s = scenario();
        JsonNode current = api.ok(INSTRUCTOR, get("/api/v1/questions/{id}", s.second()));
        Map<String, Object> replaced = questionBody(s.course().id(), s.competency(), "SINGLE", 2);
        api.ok(INSTRUCTOR, api.put(replaced, "/api/v1/questions/{id}?expectedVersion={v}", s.second(),
            current.path("rowVersion").asLong()));

        JsonNode preview = api.ok(INSTRUCTOR, api.post(request(s.second(), false), "/api/v1/quizzes/{id}/regrade",
            s.quizId()));
        assertThat(preview.path("skipped").asInt()).isEqualTo(2);
        assertThat(preview.path("applied").asInt()).isZero();
        assertThat(preview.path("items").findValuesAsText("outcome")).containsOnly("SKIPPED_OPTIONS_CHANGED");
    }

    @Test
    void validationAndPermissions() {
        Scenario s = scenario();
        String url = "/api/v1/quizzes/{id}/regrade";
        long foreign = fixtures.question(fixtures.course().id(), s.competency(), "SINGLE", 0);
        JsonNode other = api.error(INSTRUCTOR, api.post(request(foreign, true), url, s.quizId()),
            "ASSESSMENT-INVALID_REFERENCE");
        assertThat(other.path("errors").get(0).path("field").asText()).isEqualTo("questionId");
        JsonNode blank = api.error(INSTRUCTOR, api.post(Map.of("questionId", s.first(), "reason", ""), url,
            s.quizId()), "CORE-VALIDATION_FAILED");
        assertThat(blank.path("errors").get(0).path("field").asText()).isEqualTo("reason");
        api.http(LEARNER, api.post(request(s.first(), true), url, s.quizId()), 403, "CORE-ACCESS_DENIED");
        api.error(INSTRUCTOR, api.post(request(s.first(), true), url, 999_999_999L), "ASSESSMENT-NOT_FOUND");
    }

    /** Làm và nộp một lượt: chọn phương án theo chỉ số cho từng câu (đề không trộn). */
    private long attempt(String user, long quizId, int... choices) {
        JsonNode session = api.ok(user, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        for (int i = 0; i < choices.length; i++) {
            JsonNode question = session.path("questions").get(i);
            api.ok(user, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(choices[i])
                .path("id").asLong())), "/api/v1/me/quiz-attempts/{a}/questions/{q}", attemptId,
                question.path("attemptQuestionId").asLong()));
        }
        api.ok(user, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", attemptId));
        return attemptId;
    }

    /** Sửa đáp án đúng tại chỗ (giữ ID phương án) sang chỉ số mới. */
    private void moveKey(long questionId, int correctIndex) {
        JsonNode current = api.ok(INSTRUCTOR, get("/api/v1/questions/{id}", questionId));
        Map<String, Object> body = questionBody(current.path("courseId").asLong(), current.path("competencyId")
            .asLong(), "SINGLE", correctIndex);
        body.put("content", current.path("content").asText());
        List<Map<String, Object>> options = new ArrayList<>();
        for (JsonNode option : current.path("options")) {
            Map<String, Object> value = new HashMap<>();
            value.put("id", option.path("id").asLong());
            value.put("content", option.path("content").asText());
            value.put("correct", option.path("sortNo").asInt() == correctIndex);
            options.add(value);
        }
        body.put("options", options);
        api.ok(INSTRUCTOR, api.put(body, "/api/v1/questions/{id}?expectedVersion={v}", questionId,
            current.path("rowVersion").asLong()));
    }

    private static Map<String, Object> request(long questionId, Boolean dryRun) {
        Map<String, Object> body = new HashMap<>();
        body.put("questionId", questionId);
        body.put("reason", "Sửa đáp án sai");
        body.put("dryRun", dryRun);
        return body;
    }

    private static Map<Long, JsonNode> byAttempt(JsonNode result) {
        Map<Long, JsonNode> items = new HashMap<>();
        result.path("items").forEach(item -> items.put(item.path("attemptId").asLong(), item));
        return items;
    }

    private BigDecimal score(long attemptId) {
        return jdbc.queryForObject("SELECT score_percent FROM learning_hub.quiz_attempts WHERE attempt_id = ?",
            BigDecimal.class, attemptId);
    }
}
