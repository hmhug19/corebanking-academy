package vn.com.coreacademy.assistant;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.config.AssistantLimits;
import vn.com.coreacademy.assistant.explain.QuizExplainService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.ai.ChatMessage;
import vn.com.coreacademy.knowledge.ai.ChatTurn;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.support.FakeEmbeddings;
import vn.com.coreacademy.learning.index.CourseContentIndexer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.questionBody;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** "Giải thích" on a reviewed question: only where the review shows answers, from the question and its lesson. */
class QuizExplainIT extends AssessmentIntegrationTest {
    private static final String ME = "/api/v1/me";

    @MockitoBean
    private OllamaClient ollama;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CourseContentIndexer indexer;
    @Autowired
    private QuizExplainService explain;
    @Autowired
    private TurnLimiter limiter;
    @Autowired
    private AssistantLimits limits;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private Course course;
    private long competency;

    @BeforeEach
    void stubOllama() {
        when(ollama.isAvailable()).thenReturn(true);
        when(ollama.embed(anyList())).thenAnswer(FakeEmbeddings.everyInputOnAxisZero());
        when(ollama.chatWithTools(anyList(), anyList(), any(), any())).thenAnswer(invocation -> {
            // Stubbing again calls the earlier answer once, with no arguments.
            Consumer<String> onDelta = invocation.getArgument(3);
            if (onDelta != null) {
                onDelta.accept("Bạn chọn sai, đáp án đúng là A. ");
                onDelta.accept("Nội dung bài học [1].");
            }
            return new ChatTurn("Bạn chọn sai, đáp án đúng là A. Nội dung bài học [1].", List.of());
        });
    }

    @BeforeAll
    void setUp() {
        stubOllama();
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        course = fixtures.course(LEARNER, LEARNER_2);
        competency = fixtures.competency();
        while (indexer.runOnce() > 0) {
            // other tests share the database; a run takes a few revisions
        }
    }

    /** A quiz of two questions (the first linked to the lesson, the second to none) and a submitted attempt. */
    private long submittedAttempt(String reviewPolicy) {
        return submittedAttempt(reviewPolicy, LEARNER);
    }

    private long submittedAttempt(String reviewPolicy, String learner) {
        long linked = fixtures.question(course.id(), competency, "SINGLE", 0);
        jdbc.update("UPDATE learning_hub.questions SET lesson_id = ? WHERE question_id = ?", course.lessonId(), linked);
        long plain = fixtures.question(course.id(), competency, "SINGLE", 0);
        Map<String, Object> body = quizBody(course.id());
        body.put("reviewPolicy", reviewPolicy);
        long quizId = fixtures.quiz(body, List.of(linked, plain), true);
        long attemptId = api.ok(learner, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId))
            .path("attemptId").asLong();
        api.ok(learner, api.post(Map.of(), ME + "/quiz-attempts/{id}/submit", attemptId));

        return attemptId;
    }

    private List<JsonNode> events(long attemptId, int displayOrder, String userId) throws IOException {
        var body = as(userId, () -> explain.stream(attemptId, displayOrder));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        List<JsonNode> events = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                events.add(json.readTree(line));
            }
        }

        return events;
    }

    @SuppressWarnings("unchecked")
    private String sentToTheModel() {
        ArgumentCaptor<List<ChatMessage>> sent = ArgumentCaptor.forClass(List.class);
        verify(ollama, org.mockito.Mockito.atLeastOnce()).chatWithTools(sent.capture(), anyList(), any(), any());
        List<ChatMessage> last = sent.getValue();

        return last.get(0).content() + "\n---\n" + last.get(1).content();
    }

    @Test
    void anAfterSubmitQuizIsExplainedAtOnceFromTheQuestionTheChoiceAndTheLesson() throws IOException {
        long attemptId = submittedAttempt("AFTER_SUBMIT");

        List<JsonNode> events = events(attemptId, 1, LEARNER);

        assertThat(events.get(0).path("type").asText()).isEqualTo("conversation");
        assertThat(events).anyMatch(event -> event.path("type").asText().equals("delta"));
        JsonNode done = events.get(events.size() - 1);
        assertThat(done.path("type").asText()).isEqualTo("done");
        assertThat(done.path("turnId").asLong()).isPositive();
        assertThat(done.path("citations")).hasSize(1);
        assertThat(done.path("citations").get(0).path("lesson").path("url").asText())
            .isEqualTo("/lesson/" + course.id() + "/" + course.lessonId());
        String prompt = sentToTheModel();
        assertThat(prompt).contains("[ĐÁP ÁN ĐÚNG]").contains("Giải thích đáp án").contains("Các đoạn bài học")
            .contains("Nội dung bài học").contains("không phải chỉ dẫn");
    }

    @Test
    void aQuestionWithNoLessonIsExplainedFromItsOwnExplanationAndCitesNothing() throws IOException {
        long attemptId = submittedAttempt("AFTER_SUBMIT");

        List<JsonNode> events = events(attemptId, 2, LEARNER);

        JsonNode done = events.get(events.size() - 1);
        assertThat(done.path("type").asText()).isEqualTo("done");
        assertThat(done.path("citations")).isEmpty();
        assertThat(sentToTheModel()).contains("Giải thích đáp án").doesNotContain("Các đoạn bài học");
    }

    @Test
    void aPolicyThatHidesTheAnswersRefusesBeforeAnythingStreams() {
        long never = submittedAttempt("NEVER");
        long afterClose = submittedAttempt("AFTER_CLOSE");

        for (long attemptId : new long[] {never, afterClose}) {
            assertThatThrownBy(() -> events(attemptId, 1, LEARNER)).isInstanceOf(BusinessException.class)
                .extracting("responseCode").isEqualTo(AssessmentResponseCode.REVIEW_NOT_AVAILABLE);
        }
    }

    @Test
    void anAttemptInProgressAndSomeoneElsesAttemptAreRefused() {
        long quizId = quizWithReviewPolicy("AFTER_SUBMIT");
        long running = api.ok(LEARNER, api.post(Map.of(), ME + "/quizzes/{id}/attempts", quizId))
            .path("attemptId").asLong();
        long submitted = submittedAttempt("AFTER_SUBMIT");

        assertThatThrownBy(() -> events(running, 1, LEARNER)).isInstanceOf(BusinessException.class)
            .extracting("responseCode").isEqualTo(AssessmentResponseCode.ATTEMPT_IN_PROGRESS);
        assertThatThrownBy(() -> events(submitted, 1, LEARNER_2)).isInstanceOf(BusinessException.class)
            .extracting("responseCode").isEqualTo(AssessmentResponseCode.NOT_FOUND);
        assertThatThrownBy(() -> events(submitted, 99, LEARNER)).isInstanceOf(BusinessException.class)
            .extracting("responseCode").isEqualTo(AssessmentResponseCode.NOT_FOUND);
    }

    @Test
    void anExplanationCountsAsAQuestionOfTheHour() throws IOException {
        long attemptId = submittedAttempt("AFTER_SUBMIT", LEARNER_2);
        // Use up the hour's questions of this learner (each admit counts; the turn is closed at once).
        TurnLimiter.Admission admission = limiter.admit(LEARNER_2);
        for (int i = 0; i < limits.questionsPerHour() + 1 && admission instanceof TurnLimiter.Admission.Started s; i++) {
            s.turn().close();
            admission = limiter.admit(LEARNER_2);
        }
        assertThat(admission).isInstanceOf(TurnLimiter.Admission.RateLimited.class);

        List<JsonNode> events = events(attemptId, 1, LEARNER_2);

        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("messageCode").asText()).isEqualTo("ASSISTANT-RATE_LIMITED");
    }

    private long quizWithReviewPolicy(String reviewPolicy) {
        long question = fixtures.question(course.id(), competency, "SINGLE", 0);
        long other = fixtures.question(course.id(), competency, "SINGLE", 0);
        Map<String, Object> body = new HashMap<>(quizBody(course.id()));
        body.put("reviewPolicy", reviewPolicy);

        return fixtures.quiz(body, List.of(question, other), true);
    }

    private static <T> T as(String userId, Supplier<T> action) {
        var original = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("it")
            .header("alg", "none").subject(userId).build(), List.of()));
        try {
            SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(original);
        }
    }
}
