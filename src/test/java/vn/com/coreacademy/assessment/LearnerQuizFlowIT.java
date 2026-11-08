package vn.com.coreacademy.assessment;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.service.QuizAutoSubmitJob;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.delete;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.questionBody;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** Luồng làm bài của học viên và quản trị lượt thi, gọi đúng thứ tự FE sẽ gọi. */
class LearnerQuizFlowIT extends AssessmentIntegrationTest {
    private static final String ME = "/api/v1/me";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private QuizAutoSubmitJob autoSubmitJob;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private Course course;
    private long competency;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        course = fixtures.course(LEARNER, LEARNER_2);
        competency = fixtures.competency();
    }

    /** Đề 2 câu: SINGLE đúng A, MULTIPLE đúng A+C; không trộn để biết vị trí đáp án. */
    private long quiz(Map<String, Object> overrides) {
        long single = fixtures.question(course.id(), competency, "SINGLE", 0);
        long multiple = fixtures.question(course.id(), competency, "MULTIPLE", 0, 2);
        Map<String, Object> body = quizBody(course.id());
        body.putAll(overrides);
        return fixtures.quiz(body, List.of(single, multiple), true);
    }

    @Test
    void happyPathFromIntroToResult() throws Exception {
        long quizId = quiz(Map.of());

        JsonNode intro = api.ok(LEARNER, get(ME + "/quizzes/{id}", quizId));
        assertThat(intro.path("canStart").asBoolean()).isTrue();
        assertThat(intro.path("blockReason").isNull()).isTrue();
        assertThat(intro.path("serverTime").isTextual()).isTrue();
        assertThat(intro.path("course").path("id").asLong()).isEqualTo(course.id());
        assertThat(intro.path("course").path("title").asText()).isNotBlank();
        assertThat(intro.path("quiz").path("hasMultipleChoice").asBoolean()).isTrue();
        assertThat(intro.path("quiz").has("passPercent")).isTrue();
        assertThat(intro.path("summary").path("attemptsLeft").asInt()).isEqualTo(3);
        assertThat(intro.path("summary").path("attemptsUsed").asInt()).isZero();
        assertThat(intro.path("summary").path("runningAttemptId").isNull()).isTrue();

        MockHttpServletResponse raw = api.raw(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        String rawSession = raw.getContentAsString(StandardCharsets.UTF_8);
        assertThat(rawSession).as("đề đang thi không được lộ đáp án/giải thích")
            .doesNotContain("correct").doesNotContain("explanation").doesNotContain("Giải thích");
        JsonNode session = json.readTree(rawSession).path("data");
        long attemptId = session.path("attemptId").asLong();
        assertThat(session.path("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(session.path("attemptNo").asInt()).isEqualTo(1);
        assertThat(session.path("expired").asBoolean()).isFalse();
        assertThat(session.path("totalQuestions").asInt()).isEqualTo(2);
        Instant started = Instant.parse(session.path("startedAt").asText());
        assertThat(Instant.parse(session.path("deadlineAt").asText())).isEqualTo(started.plus(15, ChronoUnit.MINUTES));
        JsonNode q1 = session.path("questions").get(0);
        JsonNode q2 = session.path("questions").get(1);
        assertThat(q1.fieldNames()).toIterable().containsExactlyInAnyOrder("attemptQuestionId", "displayOrder",
            "questionType", "content", "code", "options", "selectedOptionIds", "flagged");
        assertThat(q1.path("options").get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("id", "content");
        assertThat(q1.path("selectedOptionIds")).isEmpty();
        assertThat(q2.path("questionType").asText()).isEqualTo("MULTIPLE");

        JsonNode resumed = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        assertThat(resumed.path("attemptId").asLong()).as("bắt đầu lại trả đúng lượt đang làm").isEqualTo(attemptId);
        JsonNode running = api.ok(LEARNER, get(ME + "/quizzes/{id}", quizId));
        assertThat(running.path("summary").path("runningAttemptId").asLong()).isEqualTo(attemptId);
        assertThat(running.path("summary").path("attemptsUsed").asInt()).isEqualTo(1);
        assertThat(running.path("canStart").asBoolean()).isTrue();

        long aq1 = q1.path("attemptQuestionId").asLong();
        long aq2 = q2.path("attemptQuestionId").asLong();
        JsonNode receipt = answer(LEARNER, attemptId, aq1, Map.of("selectedOptionIds", List.of(option(q1, 0))));
        assertThat(receipt.fieldNames()).toIterable().containsExactlyInAnyOrder("attemptQuestionId",
            "selectedOptionIds", "flagged", "savedAt", "deadlineAt", "serverTime");
        assertThat(receipt.path("selectedOptionIds").get(0).asLong()).isEqualTo(option(q1, 0));
        JsonNode flagged = answer(LEARNER, attemptId, aq2, Map.of("flagged", true));
        assertThat(flagged.path("flagged").asBoolean()).isTrue();
        assertThat(flagged.path("selectedOptionIds")).isEmpty();
        answer(LEARNER, attemptId, aq2, Map.of("selectedOptionIds", List.of(option(q2, 0))));

        JsonNode reloaded = api.ok(LEARNER, get(ME + "/quiz-attempts/{id}", attemptId));
        assertThat(reloaded.path("questions").get(0).path("selectedOptionIds").get(0).asLong())
            .isEqualTo(option(q1, 0));
        assertThat(reloaded.path("questions").get(1).path("flagged").asBoolean()).isTrue();
        api.error(LEARNER, get(ME + "/quiz-attempts/{id}/result", attemptId), "ASSESSMENT-ATTEMPT_IN_PROGRESS");

        JsonNode result = api.ok(LEARNER, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit", attemptId));
        assertThat(result.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(result.path("submitType").asText()).isEqualTo("MANUAL");
        assertThat(result.path("correctCount").asInt()).isEqualTo(1);
        assertThat(result.path("rawScore").decimalValue()).isEqualByComparingTo("1");
        assertThat(result.path("maxScore").decimalValue()).isEqualByComparingTo("2");
        assertThat(result.path("scorePercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(result.path("passed").asBoolean()).as("50% >= ngưỡng 50%").isTrue();
        assertThat(result.path("durationSeconds").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(result.path("reviewAvailable").asBoolean()).isTrue();
        JsonNode review = result.path("review");
        assertThat(review).hasSize(2);
        assertThat(review.get(0).path("correct").asBoolean()).isTrue();
        assertThat(review.get(0).path("explanation").asText()).isEqualTo("Giải thích đáp án");
        assertThat(review.get(1).path("correct").asBoolean()).isFalse();
        assertThat(review.get(1).path("options").get(2).path("correct").asBoolean()).isTrue();
        assertThat(review.get(1).path("options").get(0).path("selected").asBoolean()).isTrue();
        JsonNode competencies = result.path("competencies");
        assertThat(competencies).hasSize(1);
        assertThat(competencies.get(0).path("totalQuestions").asInt()).isEqualTo(2);
        assertThat(competencies.get(0).path("correctCount").asInt()).isEqualTo(1);
        assertThat(competencies.get(0).path("name").asText()).isNotBlank();
        assertThat(result.path("summary").path("passed").asBoolean()).isTrue();

        JsonNode repeated = api.ok(LEARNER, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit", attemptId));
        assertThat(repeated.path("submittedAt").asText()).as("nộp lại là idempotent")
            .isEqualTo(result.path("submittedAt").asText());
        assertThat(api.ok(LEARNER, get(ME + "/quiz-attempts/{id}/result", attemptId)).path("scorePercent")
            .decimalValue()).isEqualByComparingTo("50");
        api.error(LEARNER, api.patch(Map.of("flagged", false), ME + "/quiz-attempts/{a}/questions/{q}", attemptId,
            aq1), "ASSESSMENT-ATTEMPT_CLOSED");

        JsonNode after = api.ok(LEARNER, get(ME + "/quizzes/{id}", quizId));
        assertThat(after.path("canStart").asBoolean()).isFalse();
        assertThat(after.path("blockReason").asText()).isEqualTo("PASSED");
        api.error(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId), "ASSESSMENT-ALREADY_PASSED");

        JsonNode history = api.ok(LEARNER, get(ME + "/quizzes/{id}/attempts", quizId));
        assertThat(history.path("attempts")).hasSize(1);
        assertThat(history.path("attempts").get(0).path("scorePercent").decimalValue()).isEqualByComparingTo("50");
        JsonNode mine = api.ok(LEARNER, get(ME + "/quiz-attempts?quizId={id}", quizId));
        assertThat(mine.path("totalElements").asLong()).isEqualTo(1);
        assertThat(mine.path("items").get(0).path("quizTitle").asText()).isNotBlank();

        MockHttpServletResponse csv = api.raw(LEARNER, get(ME + "/quiz-attempts/export?quizId={id}", quizId));
        assertThat(csv.getStatus()).isEqualTo(200);
        assertThat(csv.getContentType()).startsWith("text/csv");
        assertThat(csv.getHeader("Content-Disposition")).contains("quiz-attempts.csv");
        String text = csv.getContentAsString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Bài kiểm tra,Lượt,");
        assertThat(text.split("\r\n")).hasSize(2);

        api.error(INSTRUCTOR, delete("/api/v1/quizzes/{id}?expectedVersion={v}", quizId,
            fixtures.quizVersion(quizId)), "ASSESSMENT-QUIZ_IN_USE");
        JsonNode stats = api.ok(INSTRUCTOR, get("/api/v1/quizzes?courseId={c}", course.id()));
        JsonNode row = null;
        for (JsonNode item : stats.path("items")) {
            if (item.path("id").asLong() == quizId) {
                row = item;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("gradedAttempts").asLong()).isEqualTo(1);
        assertThat(row.path("passedAttempts").asLong()).isEqualTo(1);
    }

    @Test
    void answerPatchValidation() {
        long quizId = quiz(Map.of());
        JsonNode session = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        JsonNode q1 = session.path("questions").get(0);
        long aq1 = q1.path("attemptQuestionId").asLong();
        String url = ME + "/quiz-attempts/{a}/questions/{q}";

        JsonNode empty = api.error(LEARNER, api.patch(Map.of(), url, attemptId, aq1), "CORE-VALIDATION_FAILED");
        assertThat(empty.path("errors").get(0).path("code").asText()).isEqualTo("EMPTY_UPDATE");
        JsonNode unknown = api.error(LEARNER, api.patch(Map.of("score", 10), url, attemptId, aq1),
            "CORE-VALIDATION_FAILED");
        assertThat(unknown.path("errors").get(0).path("code").asText()).isEqualTo("UNSUPPORTED_FIELD");
        Map<String, Object> nullSelection = new HashMap<>();
        nullSelection.put("selectedOptionIds", null);
        JsonNode required = api.error(LEARNER, api.patch(nullSelection, url, attemptId, aq1),
            "CORE-VALIDATION_FAILED");
        assertThat(required.path("errors").get(0).path("code").asText()).isEqualTo("REQUIRED");
        JsonNode foreign = api.error(LEARNER, api.patch(Map.of("selectedOptionIds", List.of(option(
            session.path("questions").get(1), 0))), url, attemptId, aq1), "CORE-VALIDATION_FAILED");
        assertThat(foreign.path("errors").get(0).path("field").asText()).isEqualTo("selectedOptionIds");
        api.error(LEARNER, api.patch(Map.of("selectedOptionIds", List.of(option(q1, 0), option(q1, 1))), url,
            attemptId, aq1), "CORE-VALIDATION_FAILED");
        api.error(LEARNER, api.patch("{\"selectedOptionIds\":\"abc\"}", url, attemptId, aq1),
            "CORE-VALIDATION_FAILED");

        JsonNode cleared = api.ok(LEARNER, api.patch(Map.of("selectedOptionIds", List.of(option(q1, 1),
            option(q1, 1))), url, attemptId, aq1));
        assertThat(cleared.path("selectedOptionIds")).as("bỏ trùng").hasSize(1);
        assertThat(api.ok(LEARNER, api.patch(Map.of("selectedOptionIds", List.of()), url, attemptId, aq1))
            .path("selectedOptionIds")).isEmpty();
        assertThat(jdbc.queryForObject("""
            SELECT change_count FROM learning_hub.attempt_answers WHERE attempt_question_id = ?
            """, Integer.class, aq1)).isEqualTo(1);

        long otherQuiz = quiz(Map.of());
        long otherQuestion = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", otherQuiz))
            .path("questions").get(0).path("attemptQuestionId").asLong();
        api.error(LEARNER, api.patch(Map.of("flagged", true), url, attemptId, otherQuestion), "ASSESSMENT-NOT_FOUND");
    }

    @Test
    void attemptsAreIsolatedPerLearnerAndRoleChecked() {
        long quizId = quiz(Map.of());
        JsonNode session = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        long aq = session.path("questions").get(0).path("attemptQuestionId").asLong();

        api.error(LEARNER_2, get(ME + "/quiz-attempts/{id}", attemptId), "ASSESSMENT-NOT_FOUND");
        api.error(LEARNER_2, api.patch(Map.of("flagged", true), ME + "/quiz-attempts/{a}/questions/{q}", attemptId,
            aq), "ASSESSMENT-NOT_FOUND");
        api.error(LEARNER_2, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit", attemptId),
            "ASSESSMENT-NOT_FOUND");
        api.error(LEARNER_2, get(ME + "/quiz-attempts/{id}/result", attemptId), "ASSESSMENT-NOT_FOUND");
        assertThat(api.ok(LEARNER_2, get(ME + "/quizzes/{id}/attempts", quizId)).path("attempts")).isEmpty();
        assertThat(api.ok(LEARNER_2, get(ME + "/quiz-attempts?quizId={id}", quizId)).path("totalElements")
            .asLong()).isZero();

        api.error(INSTRUCTOR, get(ME + "/quizzes/{id}", quizId), "LEARNING-NOT_AVAILABLE");
        api.http(ADMIN, get(ME + "/quizzes/{id}", quizId), 403, "CORE-ACCESS_DENIED");
        api.http(null, get(ME + "/quizzes/{id}", quizId), 401, "CORE-UNAUTHENTICATED");

        long draft = fixtures.quiz(quizBody(course.id()), List.of(
            fixtures.question(course.id(), competency, "SINGLE", 0),
            fixtures.question(course.id(), competency, "SINGLE", 0)), false);
        api.error(LEARNER, get(ME + "/quizzes/{id}", draft), "ASSESSMENT-NOT_FOUND");
        api.error(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", draft), "ASSESSMENT-NOT_FOUND");
        api.error(LEARNER, get(ME + "/quizzes/{id}", 999_999_999L), "ASSESSMENT-NOT_FOUND");
    }

    @Test
    void attemptLimitGrantVoidAndForceSubmit() {
        long quizId = quiz(Map.of("maxAttempts", 1, "passPercent", 100));
        long first = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId)).path("attemptId")
            .asLong();
        JsonNode failed = api.ok(LEARNER, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit", first));
        assertThat(failed.path("passed").asBoolean()).isFalse();
        assertThat(failed.path("scorePercent").decimalValue()).isEqualByComparingTo("0");

        JsonNode blocked = api.ok(LEARNER, get(ME + "/quizzes/{id}", quizId));
        assertThat(blocked.path("blockReason").asText()).isEqualTo("NO_ATTEMPTS");
        assertThat(blocked.path("summary").path("attemptsLeft").asInt()).isZero();
        api.error(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId), "ASSESSMENT-NO_ATTEMPTS_LEFT");

        String grants = "/api/v1/quizzes/{id}/grants";
        String strictGrant = grants + "?requireExhausted=true";
        String exhausted = "/api/v1/quizzes/{id}/exhausted-learners";
        api.http(LEARNER, get(exhausted, quizId), 403, "CORE-ACCESS_DENIED");
        JsonNode candidates = api.ok(INSTRUCTOR, get(exhausted + "?userId={u}&size=1", quizId, LEARNER));
        assertThat(candidates.path("totalElements").asLong()).isEqualTo(1);
        assertThat(candidates.path("items").get(0).path("userId").asText()).isEqualTo(LEARNER);
        assertThat(candidates.path("items").get(0).path("attemptsUsed").asLong()).isEqualTo(1);
        JsonNode notEnrolled = api.error(INSTRUCTOR, api.post(grant(INSTRUCTOR), grants, quizId),
            "ASSESSMENT-INVALID_REFERENCE");
        assertThat(notEnrolled.path("errors").get(0).path("field").asText()).isEqualTo("userId");
        api.error(INSTRUCTOR, api.post(Map.of("userId", LEARNER, "extraAttempts", 11, "reason", "x"), grants, quizId),
            "CORE-VALIDATION_FAILED");
        api.http(LEARNER, api.post(grant(LEARNER), grants, quizId), 403, "CORE-ACCESS_DENIED");
        JsonNode granted = api.ok(INSTRUCTOR, api.post(grant(LEARNER), strictGrant, quizId));
        assertThat(granted.path("userName").asText()).isEqualTo("Học viên tiền gửi Demo");
        assertThat(granted.path("grantedBy").asText()).isEqualTo(INSTRUCTOR);
        assertThat(granted.path("revoked").asBoolean()).isFalse();
        api.error(INSTRUCTOR, api.post(grant(LEARNER), strictGrant, quizId), "ASSESSMENT-CONFLICT");
        assertThat(api.ok(INSTRUCTOR, get(exhausted, quizId)).path("totalElements").asLong()).isZero();
        assertThat(api.ok(LEARNER, get(ME + "/quizzes/{id}", quizId)).path("summary").path("attemptsLeft").asInt())
            .isEqualTo(1);

        JsonNode second = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        long secondId = second.path("attemptId").asLong();
        assertThat(second.path("attemptNo").asInt()).isEqualTo(2);

        JsonNode list = api.ok(INSTRUCTOR, get("/api/v1/quizzes/{id}/attempts?sort=id,asc", quizId));
        assertThat(list.path("totalElements").asLong()).isEqualTo(2);
        assertThat(list.path("items").get(0).path("userName").asText()).isEqualTo("Học viên tiền gửi Demo");
        assertThat(api.ok(INSTRUCTOR, get("/api/v1/quizzes/{id}/attempts?status=IN_PROGRESS", quizId))
            .path("totalElements").asLong()).isEqualTo(1);
        api.error(INSTRUCTOR, get("/api/v1/quizzes/{id}/attempts?status=DONE", quizId), "CORE-VALIDATION_FAILED");

        JsonNode detail = api.ok(INSTRUCTOR, get("/api/v1/quiz-attempts/{id}", first));
        assertThat(detail.path("userId").asText()).isEqualTo(LEARNER);
        assertThat(detail.path("clientIp").asText()).isNotBlank();
        assertThat(detail.path("questions")).hasSize(2);
        assertThat(detail.path("questions").get(0).path("options").get(0).has("correct")).isTrue();
        assertThat(detail.path("questions").get(0).has("changeCount")).isTrue();
        api.http(LEARNER, get("/api/v1/quiz-attempts/{id}", first), 403, "CORE-ACCESS_DENIED");

        api.error(INSTRUCTOR, api.post(Map.of("reason", ""), "/api/v1/quiz-attempts/{id}/void", secondId),
            "CORE-VALIDATION_FAILED");
        JsonNode voided = api.ok(INSTRUCTOR, api.post(Map.of("reason", "Sự cố mạng"),
            "/api/v1/quiz-attempts/{id}/void", secondId));
        assertThat(voided.path("status").asText()).isEqualTo("VOIDED");
        assertThat(voided.path("voidReason").asText()).isEqualTo("Sự cố mạng");
        assertThat(voided.path("voidedBy").asText()).isEqualTo(INSTRUCTOR);
        api.error(INSTRUCTOR, api.post(Map.of("reason", "lần 2"), "/api/v1/quiz-attempts/{id}/void", secondId),
            "ASSESSMENT-CONFLICT");

        JsonNode third = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        assertThat(third.path("attemptNo").asInt()).as("lượt VOIDED không tính vào số lượt").isEqualTo(3);
        long thirdId = third.path("attemptId").asLong();
        JsonNode forced = api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/quiz-attempts/{id}/force-submit", thirdId));
        assertThat(forced.path("status").asText()).isEqualTo("SUBMITTED");
        assertThat(forced.path("submitType").asText()).isEqualTo("ADMIN");
        api.error(INSTRUCTOR, api.post(Map.of(), "/api/v1/quiz-attempts/{id}/force-submit", thirdId),
            "ASSESSMENT-CONFLICT");

        long grantId = granted.path("id").asLong();
        JsonNode revoked = api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/quiz-attempt-grants/{id}/revoke", grantId));
        assertThat(revoked.path("revoked").asBoolean()).isTrue();
        assertThat(revoked.path("revokedBy").asText()).isEqualTo(INSTRUCTOR);
        api.error(INSTRUCTOR, api.post(Map.of(), "/api/v1/quiz-attempt-grants/{id}/revoke", grantId),
            "ASSESSMENT-CONFLICT");
        JsonNode history = api.ok(INSTRUCTOR, get(grants + "?userId={u}", quizId, LEARNER));
        assertThat(history).hasSize(1);
        assertThat(api.ok(LEARNER, get(ME + "/quizzes/{id}", quizId)).path("summary").path("attemptsLeft").asInt())
            .as("thu hồi không làm số lượt âm").isZero();
        assertThat(api.ok(INSTRUCTOR, get(exhausted, quizId)).path("items").get(0).path("attemptsUsed").asLong())
            .as("không tính lượt bị hủy, có xét thu hồi cấp thêm").isEqualTo(2);
    }

    @Test
    void timeoutIsGradedAsAutoSubmittedWithServerDeadline() {
        long quizId = quiz(Map.of("maxAttempts", 5, "passPercent", 100));
        JsonNode session = api.ok(LEARNER_2, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        long aq = session.path("questions").get(0).path("attemptQuestionId").asLong();
        answer(LEARNER_2, attemptId, aq, Map.of("selectedOptionIds", List.of(option(session.path("questions").get(0),
            0))));
        expire(attemptId);

        api.error(LEARNER_2, api.patch(Map.of("flagged", true), ME + "/quiz-attempts/{a}/questions/{q}", attemptId,
            aq), "ASSESSMENT-ATTEMPT_CLOSED");
        assertThat(api.ok(LEARNER_2, get(ME + "/quiz-attempts/{id}", attemptId)).path("expired").asBoolean())
            .isTrue();
        assertThat(api.ok(LEARNER_2, get(ME + "/quizzes/{id}", quizId)).path("summary").path("runningAttemptId")
            .isNull()).isTrue();
        JsonNode result = api.ok(LEARNER_2, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit", attemptId));
        assertThat(result.path("status").asText()).isEqualTo("AUTO_SUBMITTED");
        assertThat(result.path("submitType").asText()).isEqualTo("TIMEOUT");
        assertThat(result.path("correctCount").asInt()).as("chấm với đáp án đã lưu").isEqualTo(1);
        Instant deadline = jdbc.queryForObject(
            "SELECT deadline_at FROM learning_hub.quiz_attempts WHERE attempt_id = ?", Timestamp.class, attemptId)
            .toInstant();
        assertThat(Instant.parse(result.path("submittedAt").asText())).isEqualTo(deadline);

        long expiredRunning = api.ok(LEARNER_2, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId))
            .path("attemptId").asLong();
        expire(expiredRunning);
        JsonNode next = api.ok(LEARNER_2, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        assertThat(next.path("attemptId").asLong()).as("lượt quá hạn được tự nộp trước khi tạo lượt mới")
            .isNotEqualTo(expiredRunning);
        assertThat(status(expiredRunning)).isEqualTo("AUTO_SUBMITTED");

        long byJob = next.path("attemptId").asLong();
        expire(byJob);
        autoSubmitJob.run();
        assertThat(status(byJob)).isEqualTo("AUTO_SUBMITTED");
        autoSubmitJob.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.quiz_attempts WHERE quiz_id = ? AND "
            + "status = 'IN_PROGRESS'", Integer.class, quizId)).isZero();
    }

    @Test
    void closingQuizSubmitsRunningAttemptsAndBlocksNewOnes() {
        long quizId = quiz(Map.of());
        long attemptId = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId))
            .path("attemptId").asLong();
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", fixtures.quizVersion(quizId)),
            "/api/v1/quizzes/{id}/close", quizId));

        JsonNode result = api.ok(LEARNER, get(ME + "/quiz-attempts/{id}/result", attemptId));
        assertThat(result.path("status").asText()).isEqualTo("AUTO_SUBMITTED");
        assertThat(result.path("submitType").asText()).isEqualTo("QUIZ_CLOSED");
        JsonNode intro = api.ok(LEARNER, get(ME + "/quizzes/{id}", quizId));
        assertThat(intro.path("quiz").path("status").asText()).isEqualTo("CLOSED");
        assertThat(intro.path("blockReason").asText()).isEqualTo("CLOSED");
        api.error(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId), "ASSESSMENT-QUIZ_CLOSED");
    }

    @Test
    void partialScoringScoreMethodLastAndReviewPolicy() {
        long multiple = fixtures.question(course.id(), competency, "MULTIPLE", 0, 1);
        Map<String, Object> body = quizBody(course.id());
        body.put("questionCount", 1);
        body.put("scoringMode", "PARTIAL");
        body.put("scoreMethod", "LAST");
        body.put("passPercent", 60);
        body.put("allowRetakeAfterPass", true);
        body.put("maxAttempts", 5);
        body.put("reviewPolicy", "NEVER");
        long quizId = fixtures.quiz(body, List.of(multiple), true);

        JsonNode half = attemptWith(LEARNER, quizId, 0);
        assertThat(half.path("scorePercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(half.path("passed").asBoolean()).isFalse();
        assertThat(half.path("reviewAvailable").asBoolean()).isFalse();
        assertThat(half.path("review").isNull()).isTrue();

        assertThat(attemptWith(LEARNER, quizId, 0, 2).path("scorePercent").decimalValue())
            .as("đúng 1 sai 1 => 0").isEqualByComparingTo("0");
        JsonNode full = attemptWith(LEARNER, quizId, 0, 1);
        assertThat(full.path("passed").asBoolean()).isTrue();
        JsonNode last = attemptWith(LEARNER, quizId);
        JsonNode summary = last.path("summary");
        assertThat(summary.path("bestScore").decimalValue()).isEqualByComparingTo("100");
        assertThat(summary.path("lastScore").decimalValue()).isEqualByComparingTo("0");
        assertThat(summary.path("finalScore").decimalValue()).as("LAST lấy lượt gần nhất").isEqualByComparingTo("0");
        assertThat(summary.path("passed").asBoolean()).isFalse();
        assertThat(summary.path("everPassed").asBoolean()).isTrue();
    }

    @Test
    void editingServedQuestionBumpsVersionButKeepsSnapshot() {
        long single = fixtures.question(course.id(), competency, "SINGLE", 0);
        long other = fixtures.question(course.id(), competency, "SINGLE", 0);
        long quizId = fixtures.quiz(quizBody(course.id()), List.of(single, other), true);
        JsonNode session = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        String original = session.path("questions").get(0).path("content").asText();

        JsonNode current = api.ok(INSTRUCTOR, get("/api/v1/questions/{id}", single));
        Map<String, Object> edit = questionBody(course.id(), competency, "SINGLE", 0);
        List<Map<String, Object>> options = new ArrayList<>();
        for (JsonNode option : current.path("options")) {
            Map<String, Object> value = new HashMap<>();
            value.put("id", option.path("id").asLong());
            value.put("content", option.path("content").asText());
            value.put("correct", option.path("sortNo").asInt() == 1);
            options.add(value);
        }
        edit.put("options", options);
        JsonNode updated = api.ok(INSTRUCTOR, api.put(edit, "/api/v1/questions/{id}?expectedVersion={v}", single,
            current.path("rowVersion").asLong()));
        assertThat(updated.path("contentVersion").asInt()).isEqualTo(2);

        JsonNode result = api.ok(LEARNER, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit", attemptId));
        assertThat(result.path("review").get(0).path("content").asText()).isEqualTo(original);
        assertThat(result.path("review").get(0).path("options").get(0).path("correct").asBoolean())
            .as("chấm theo đáp án lúc phát đề").isTrue();
    }

    @Test
    void concurrentStartsCreateOneAttempt() throws Exception {
        long quizId = quiz(Map.of());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            List<Future<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<Long> call = () -> {
                    ready.await();
                    return api.ok(LEARNER_2, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId))
                        .path("attemptId").asLong();
                };
                calls.add(pool.submit(call));
            }
            ready.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<Long> call : calls) {
                ids.add(call.get());
            }
            assertThat(ids).containsOnly(ids.get(0));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.quiz_attempts WHERE quiz_id = ?",
            Integer.class, quizId)).isEqualTo(1);
    }

    @Test
    void blockReasonsForWindowLessonsAndRetakeWait() {
        long future = quiz(Map.of("openAt", Instant.now().plus(2, ChronoUnit.DAYS).toString()));
        JsonNode notOpen = api.ok(LEARNER, get(ME + "/quizzes/{id}", future));
        assertThat(notOpen.path("blockReason").asText()).isEqualTo("NOT_OPEN");
        assertThat(notOpen.path("availableAt").isTextual()).isTrue();
        api.error(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", future), "ASSESSMENT-QUIZ_NOT_OPEN");

        long lessons = quiz(Map.of("requireLessonsCompleted", true));
        assertThat(api.ok(LEARNER, get(ME + "/quizzes/{id}", lessons)).path("blockReason").asText())
            .isEqualTo("LESSONS_REQUIRED");
        api.error(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", lessons),
            "ASSESSMENT-LESSONS_REQUIRED");

        long wait = quiz(Map.of("retakeWaitHours", 24, "passPercent", 100));
        attemptWith(LEARNER, wait);
        JsonNode waiting = api.ok(LEARNER, get(ME + "/quizzes/{id}", wait));
        assertThat(waiting.path("blockReason").asText()).isEqualTo("WAIT");
        assertThat(Instant.parse(waiting.path("availableAt").asText())).isAfter(Instant.now().plus(23,
            ChronoUnit.HOURS));
        api.error(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", wait), "ASSESSMENT-RETAKE_WAIT");
    }

    private JsonNode answer(String user, long attemptId, long attemptQuestionId, Map<String, Object> body) {
        return api.ok(user, api.patch(body, ME + "/quiz-attempts/{a}/questions/{q}", attemptId, attemptQuestionId));
    }

    /** Làm một lượt với câu đầu tiên chọn các phương án theo chỉ số rồi nộp; trả kết quả. */
    private JsonNode attemptWith(String user, long quizId, int... choices) {
        JsonNode session = api.ok(user, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId));
        JsonNode question = session.path("questions").get(0);
        List<Long> ids = new ArrayList<>();
        for (int choice : choices) {
            ids.add(option(question, choice));
        }
        if (!ids.isEmpty()) {
            answer(user, session.path("attemptId").asLong(), question.path("attemptQuestionId").asLong(),
                Map.of("selectedOptionIds", ids));
        }
        return api.ok(user, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit",
            session.path("attemptId").asLong()));
    }

    private static long option(JsonNode question, int index) {
        return question.path("options").get(index).path("id").asLong();
    }

    private static Map<String, Object> grant(String userId) {
        return Map.of("userId", userId, "extraAttempts", 1, "reason", "Sự cố khi thi");
    }

    private void expire(long attemptId) {
        jdbc.update("""
            UPDATE learning_hub.quiz_attempts
            SET started_at = now() - interval '30 minutes', deadline_at = now() - interval '1 minute'
            WHERE attempt_id = ?
            """, attemptId);
    }

    private String status(long attemptId) {
        return jdbc.queryForObject("SELECT status FROM learning_hub.quiz_attempts WHERE attempt_id = ?",
            String.class, attemptId);
    }
}
