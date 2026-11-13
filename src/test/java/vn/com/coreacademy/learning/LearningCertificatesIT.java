package vn.com.coreacademy.learning;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.learning.service.LearningQuizDirectory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/**
 * Chứng nhận là các chu kỳ ghi danh đã hoàn thành của người gọi. Chu kỳ được hoàn thành bằng SQL vì việc hoàn thành
 * là của dịch vụ tiến độ và đã có test riêng; ở đây chỉ quan tâm phần chứng nhận đọc lại từ đó.
 */
class LearningCertificatesIT extends AssessmentIntegrationTest {
    private static final String LIST = "/api/v1/me/learning/certificates";
    private static final String ONE = LIST + "/{id}";
    private static final String NOT_FOUND = "LEARNING-NOT_FOUND";

    @MockitoSpyBean
    private LearningQuizDirectory directory;

    @MockitoSpyBean
    private LearningIdentityService identity;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private long competency;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        competency = fixtures.competency();
    }

    /** Cơ sở dữ liệu dùng chung với các test khác, nên hai học viên bắt đầu mà chưa có chu kỳ nào hoàn thành. */
    @BeforeEach
    void startWithoutCompletedCycles() {
        jdbc.update("""
            update learning_hub.course_enrollments set status = 'in_progress'
            where status = 'completed' and user_id in (?, ?)
            """, LEARNER, LEARNER_2);
    }

    @Test
    void listsOnlyTheCallersCompletedCyclesNewestFirstThenHighestIdAndPages() {
        Course january = fixtures.course(LEARNER, LEARNER_2);
        Course february = fixtures.course(LEARNER);
        Course sameInstant = fixtures.course(LEARNER);
        Course inProgress = fixtures.course(LEARNER);
        long a = complete(january, LEARNER, "2026-01-10T08:00:00Z");
        long b = complete(february, LEARNER, "2026-02-10T08:00:00Z");
        long c = complete(sameInstant, LEARNER, "2026-02-10T08:00:00Z");
        long other = complete(january, LEARNER_2, "2026-03-01T08:00:00Z");
        jdbc.update("update learning_hub.course_enrollments set status = 'in_progress' where enrollment_id = ?",
            enrollment(inProgress, LEARNER));

        JsonNode page = api.ok(LEARNER, get(LIST));

        assertThat(ids(page)).as("newest first, a tie goes to the highest id").containsExactly(c, b, a);
        assertThat(page.path("totalElements").asInt()).isEqualTo(3);
        assertThat(page.path("size").asInt()).isEqualTo(20);
        assertThat(ids(api.ok(LEARNER_2, get(LIST)))).containsExactly(other);

        JsonNode first = api.ok(LEARNER, get(LIST + "?size=2&page=0"));
        assertThat(ids(first)).containsExactly(c, b);
        assertThat(first.path("totalElements").asInt()).isEqualTo(3);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(api.ok(LEARNER, get(LIST + "?size=2&page=1")))).containsExactly(a);
        assertThat(ids(api.ok(LEARNER, get(LIST + "?size=2&page=9")))).isEmpty();
        assertThat(api.ok(LEARNER, get(LIST + "?size=100")).path("size").asInt()).isEqualTo(100);
        for (String invalid : new String[] {"size=101", "size=0", "page=-1"}) {
            api.error(LEARNER, get(LIST + "?" + invalid), "CORE-VALIDATION_FAILED");
        }
    }

    @Test
    void anEmptyListIsAnEmptyPage() {
        JsonNode page = api.ok(LEARNER, get(LIST));

        assertThat(page.path("items")).isEmpty();
        assertThat(page.path("totalElements").asInt()).isZero();
    }

    @Test
    void oneCertificateCarriesEveryField() {
        Course course = fixtures.course(LEARNER);
        jdbc.update("update learning_hub.courses set code = ? where course_id = ?", "cert-lower-" + course.id(),
            course.id());
        long id = complete(course, LEARNER, "2026-05-06T07:08:09Z");
        String code = jdbc.queryForObject("select code from learning_hub.courses where course_id = ?", String.class,
            course.id());
        String title = jdbc.queryForObject("select title from learning_hub.course_revisions where revision_id = ?",
            String.class, course.revisionId());
        String fullName = jdbc.queryForObject("select full_name from learning_hub.users where user_id = ?",
            String.class, LEARNER);

        JsonNode certificate = api.ok(LEARNER, get(ONE, id));

        assertThat(certificate.fieldNames()).toIterable().containsExactly("id", "courseId", "courseCode",
            "courseTitle", "cycleNo", "revisionId", "userId", "fullName", "completedAt", "bestScorePercent",
            "verificationCode");
        assertThat(certificate.path("id").asLong()).isEqualTo(id);
        assertThat(certificate.path("courseId").asLong()).isEqualTo(course.id());
        assertThat(certificate.path("courseCode").asText()).isEqualTo(code);
        assertThat(certificate.path("courseTitle").asText()).isEqualTo(title);
        assertThat(certificate.path("cycleNo").asInt()).isEqualTo(1);
        assertThat(certificate.path("revisionId").asLong()).isEqualTo(course.revisionId());
        assertThat(certificate.path("userId").asText()).isEqualTo(LEARNER);
        assertThat(certificate.path("fullName").asText()).isEqualTo(fullName);
        assertThat(certificate.path("completedAt").asText()).startsWith("2026-05-06T07:08:09");
        assertThat(certificate.path("bestScorePercent").isNull()).as("no final exam").isTrue();
        assertThat(certificate.path("verificationCode").asText()).isEqualTo(("CBA-" + code + "-" + LEARNER + "-1").toUpperCase());
        assertThat(code).as("the course code is not upper case, the verification code is").isNotEqualTo(code.toUpperCase());
        assertThat(api.ok(LEARNER, get(LIST)).path("items").get(0)).isEqualTo(certificate);
    }

    @Test
    void refusesWhatIsNotACompletedCycleOfTheCaller() {
        Course course = fixtures.course(LEARNER, LEARNER_2);
        long mine = complete(course, LEARNER, "2026-01-10T08:00:00Z");
        long inProgress = enrollment(fixtures.course(LEARNER), LEARNER);
        long withdrawn = complete(fixtures.course(LEARNER), LEARNER, "2026-01-11T08:00:00Z");
        jdbc.update("update learning_hub.course_enrollments set status = 'withdrawn' where enrollment_id = ?",
            withdrawn);
        long noDate = complete(fixtures.course(LEARNER), LEARNER, "2026-01-12T08:00:00Z");
        jdbc.update("update learning_hub.course_enrollments set completed_at = null where enrollment_id = ?", noDate);

        api.ok(LEARNER, get(ONE, mine));
        api.error(LEARNER_2, get(ONE, mine), NOT_FOUND);
        api.error(LEARNER, get(ONE, inProgress), NOT_FOUND);
        api.error(LEARNER, get(ONE, withdrawn), NOT_FOUND);
        api.error(LEARNER, get(ONE, noDate), NOT_FOUND);
        api.error(LEARNER, get(ONE, 999_999_999L), NOT_FOUND);
        assertThat(ids(api.ok(LEARNER, get(LIST)))).as("neither is listed").containsExactly(mine);
    }

    @Test
    void theTitleStaysTheCompletedRevisionsAfterANewRevisionIsPublished() {
        Course course = fixtures.course(LEARNER);
        long id = complete(course, LEARNER, "2026-01-10T08:00:00Z");
        String completedTitle = api.ok(LEARNER, get(ONE, id)).path("courseTitle").asText();
        JsonNode draft = api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/courses/{c}/draft", course.id()));
        long newRevision = draft.path("revision").path("id").asLong();
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", draft.path("revision").path("rowVersion").asLong()),
            "/api/v1/courses/{c}/revisions/{r}/publish", course.id(), newRevision));
        jdbc.update("update learning_hub.course_revisions set title = 'Tên mới của khóa' where revision_id = ?",
            newRevision);

        JsonNode certificate = api.ok(LEARNER, get(ONE, id));

        assertThat(certificate.path("revisionId").asLong()).isEqualTo(course.revisionId());
        assertThat(certificate.path("courseTitle").asText()).isEqualTo(completedTitle).isNotEqualTo("Tên mới của khóa");
        assertThat(api.ok(LEARNER, get(LIST)).path("items").get(0).path("courseTitle").asText())
            .isEqualTo(completedTitle);
    }

    @Test
    void aMissingRevisionTitleIsNull() {
        Course course = fixtures.course(LEARNER);
        long id = complete(course, LEARNER, "2026-01-10T08:00:00Z");
        jdbc.update("update learning_hub.course_revisions set title = null where revision_id = ?",
            course.revisionId());

        JsonNode certificate = api.ok(LEARNER, get(ONE, id));

        assertThat(certificate.path("courseTitle").isNull()).isTrue();
        assertThat(certificate.path("courseCode").asText()).isNotBlank();
    }

    @Test
    void theScoreIsTheCardsBestFinalExamScoreAndNullWithoutOne() {
        Course scored = fixtures.course(LEARNER);
        long exam = quiz(scored, "FINAL_EXAM");
        jdbc.update("update learning_hub.course_completion_rules set final_quiz_id = ? where revision_id = ?",
            exam, scored.revisionId());
        attempt(LEARNER, exam, 1);
        attempt(LEARNER, exam, 2);
        attempt(LEARNER, exam, 0);
        Course practiceOnly = fixtures.course(LEARNER);
        attempt(LEARNER, quiz(practiceOnly, "PRACTICE"), 2);
        Course none = fixtures.course(LEARNER);
        long scoredId = complete(scored, LEARNER, "2026-01-10T08:00:00Z");
        long practiceId = complete(practiceOnly, LEARNER, "2026-01-11T08:00:00Z");
        long noneId = complete(none, LEARNER, "2026-01-12T08:00:00Z");

        BigDecimal card = card(LEARNER, scored).path("bestScorePercent").decimalValue();
        assertThat(card).isEqualByComparingTo("100");
        assertThat(api.ok(LEARNER, get(ONE, scoredId)).path("bestScorePercent").decimalValue())
            .isEqualByComparingTo(card);
        assertThat(api.ok(LEARNER, get(ONE, practiceId)).path("bestScorePercent").isNull())
            .as("a practice quiz is not the final exam").isTrue();
        assertThat(api.ok(LEARNER, get(ONE, noneId)).path("bestScorePercent").isNull()).isTrue();
    }

    @Test
    void twoCyclesOfOneCourseAreTwoCertificatesWithDifferentCodes() {
        Course course = fixtures.course(LEARNER);
        long first = complete(course, LEARNER, "2026-01-10T08:00:00Z");
        long second = jdbc.queryForObject("""
            insert into learning_hub.course_enrollments (course_id, revision_id, user_id, cycle_no,
                enrollment_origin, enrolled_at, completed_at, status, progress_percent, created_by, updated_by)
            select course_id, revision_id, user_id, 2, enrollment_origin, now(), '2026-03-10T08:00:00Z', 'completed',
                100, created_by, updated_by
            from learning_hub.course_enrollments where enrollment_id = ?
            returning enrollment_id
            """, Long.class, first);

        JsonNode page = api.ok(LEARNER, get(LIST));

        assertThat(ids(page)).containsExactly(second, first);
        JsonNode items = page.path("items");
        assertThat(items.get(0).path("cycleNo").asInt()).isEqualTo(2);
        assertThat(items.get(1).path("cycleNo").asInt()).isEqualTo(1);
        assertThat(items.get(0).path("verificationCode").asText()).endsWith("-" + LEARNER + "-2");
        assertThat(items.get(1).path("verificationCode").asText()).endsWith("-" + LEARNER + "-1");
        assertThat(items.get(0).path("verificationCode").asText())
            .isNotEqualTo(items.get(1).path("verificationCode").asText());
    }

    @Test
    void aPageCostsOneNameCallAndOneScoresCall() {
        complete(fixtures.course(LEARNER), LEARNER, "2026-01-10T08:00:00Z");
        complete(fixtures.course(LEARNER), LEARNER, "2026-01-11T08:00:00Z");
        complete(fixtures.course(LEARNER), LEARNER, "2026-01-12T08:00:00Z");
        Mockito.clearInvocations(directory, identity);

        JsonNode page = api.ok(LEARNER, get(LIST));

        assertThat(page.path("items")).hasSize(3);
        verify(directory, times(1)).enrollmentScores(any(), any());
        verify(identity, times(1)).fullNames(any());
    }

    @Test
    void onlyLearnersMayUseTheEndpoints() {
        api.http(ADMIN, get(LIST), 403, "CORE-ACCESS_DENIED");
        api.http(ADMIN, get(ONE, 1L), 403, "CORE-ACCESS_DENIED");
        api.http(null, get(LIST), 401, "CORE-UNAUTHENTICATED");
    }

    @Test
    void historicalScoreSurvivesClosingTheExamAndDoesNotLeakIntoAnotherCycle() {
        Course course = fixtures.course(LEARNER);
        long exam = quiz(course, "FINAL_EXAM");
        jdbc.update("update learning_hub.course_completion_rules set final_quiz_id = ? where revision_id = ?",
            exam, course.revisionId());
        attempt(LEARNER, exam, 1);
        long first = complete(course, LEARNER, "2026-01-10T08:00:00Z");
        long second = jdbc.queryForObject("""
            insert into learning_hub.course_enrollments (course_id, revision_id, user_id, cycle_no,
                enrollment_origin, enrolled_at, completed_at, status, progress_percent, created_by, updated_by)
            select course_id, revision_id, user_id, 2, enrollment_origin, now(), now(), 'completed',
                100, created_by, updated_by
            from learning_hub.course_enrollments where enrollment_id = ? returning enrollment_id
            """, Long.class, first);
        jdbc.update("update learning_hub.quizzes set status = 'CLOSED' where quiz_id = ?", exam);

        assertThat(api.ok(LEARNER, get(ONE, first)).path("bestScorePercent").decimalValue())
            .isEqualByComparingTo("50");
        assertThat(api.ok(LEARNER, get(ONE, second)).path("bestScorePercent").isNull()).isTrue();
    }

    private long enrollment(Course course, String user) {
        return jdbc.queryForObject("""
            select enrollment_id from learning_hub.course_enrollments
            where course_id = ? and user_id = ? and cycle_no = 1
            """, Long.class, course.id(), user);
    }

    private long complete(Course course, String user, String completedAt) {
        long id = enrollment(course, user);
        jdbc.update("""
            update learning_hub.course_enrollments
            set status = 'completed', completed_at = ?::timestamptz, progress_percent = 100
            where enrollment_id = ?
            """, completedAt, id);
        return id;
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private JsonNode card(String user, Course course) {
        for (JsonNode row : api.ok(user, get("/api/v1/me/learning/courses?size=100&sort=id,desc")).path("items")) {
            if (row.path("courseId").asLong() == course.id()) {
                return row;
            }
        }
        throw new AssertionError("Course " + course.id() + " is not in the list");
    }

    private long quiz(Course course, String type) {
        List<Long> questions = List.of(fixtures.question(course.id(), competency, "SINGLE", 0),
            fixtures.question(course.id(), competency, "SINGLE", 0));
        Map<String, Object> body = quizBody(course.id());
        body.put("quizType", type);
        body.put("maxAttempts", 5);
        body.put("allowRetakeAfterPass", true);
        return fixtures.quiz(body, questions, true);
    }

    /** Trả lời đúng {@code right} câu đầu của bài kiểm tra hai câu, bỏ trống phần còn lại rồi nộp bài. */
    private void attempt(String user, long quizId, int right) {
        JsonNode session = api.ok(user, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        for (int i = 0; i < right; i++) {
            JsonNode question = session.path("questions").get(i);
            api.ok(user, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(0).path("id")
                    .asLong())), "/api/v1/me/quiz-attempts/{a}/questions/{q}", attemptId,
                question.path("attemptQuestionId").asLong()));
        }
        api.ok(user, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", attemptId));
    }
}
