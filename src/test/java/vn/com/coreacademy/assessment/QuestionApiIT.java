package vn.com.coreacademy.assessment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ROOT;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.questionBody;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.unique;

class QuestionApiIT extends AssessmentIntegrationTest {
    private static final String URL = "/api/v1/questions";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private Course course;
    private long competency;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        course = fixtures.course();
        competency = fixtures.competency();
    }

    @Test
    void createSingleChoiceReturnsFullDetailForEditor() {
        Map<String, Object> body = questionBody(course.id(), competency, "SINGLE", 1);
        body.put("lessonId", course.lessonId());
        JsonNode created = api.ok(INSTRUCTOR, api.post(body, URL));

        long id = created.path("id").asLong();
        assertThat(created.path("code").asText()).isEqualTo("Q" + id);
        assertThat(created.path("courseId").asLong()).isEqualTo(course.id());
        assertThat(created.path("lessonId").asLong()).isEqualTo(course.lessonId());
        assertThat(created.path("questionType").asText()).isEqualTo("SINGLE");
        assertThat(created.path("difficulty").asText()).isEqualTo("MEDIUM");
        assertThat(created.path("points").decimalValue()).isEqualByComparingTo("1");
        assertThat(created.path("contentVersion").asInt()).isEqualTo(1);
        assertThat(created.path("rowVersion").isNumber()).isTrue();
        assertThat(created.path("usedInQuizzes").isArray()).isTrue();
        JsonNode options = created.path("options");
        assertThat(options).hasSize(4);
        assertThat(options.get(0).fieldNames()).toIterable()
            .containsExactlyInAnyOrder("id", "content", "correct", "sortNo");
        assertThat(options.get(1).path("correct").asBoolean()).isTrue();
        assertThat(options.findValues("correct").stream().filter(JsonNode::asBoolean)).hasSize(1);

        assertThat(api.ok(INSTRUCTOR, get(URL + "/{id}", id)).path("id").asLong()).isEqualTo(id);
    }

    @Test
    void choiceRulesAreEnforced() {
        JsonNode single = api.error(INSTRUCTOR, api.post(questionBody(course.id(), competency, "SINGLE", 0, 1), URL),
            "CORE-VALIDATION_FAILED");
        assertThat(single.path("errors").get(0).path("field").asText()).isEqualTo("options");

        api.error(INSTRUCTOR, api.post(questionBody(course.id(), competency, "MULTIPLE", 0), URL),
            "CORE-VALIDATION_FAILED");
        JsonNode multiple = api.ok(INSTRUCTOR, api.post(questionBody(course.id(), competency, "MULTIPLE", 0, 2),
            URL));
        assertThat(multiple.path("options").findValues("correct").stream().filter(JsonNode::asBoolean)).hasSize(2);

        Map<String, Object> oneOption = questionBody(course.id(), competency, "SINGLE", 0);
        oneOption.put("options", List.of(((List<?>) oneOption.get("options")).get(0)));
        JsonNode tooFew = api.error(INSTRUCTOR, api.post(oneOption, URL), "CORE-VALIDATION_FAILED");
        assertThat(tooFew.path("errors").get(0).path("field").asText()).isEqualTo("options");

        Map<String, Object> badEnum = questionBody(course.id(), competency, "TRUE_FALSE", 0);
        badEnum.put("difficulty", "Dễ");
        JsonNode enums = api.error(INSTRUCTOR, api.post(badEnum, URL), "CORE-VALIDATION_FAILED");
        assertThat(enums.path("errors").findValuesAsText("field")).contains("questionType", "difficulty");
    }

    @Test
    void referencesAndCodesAreChecked() {
        Map<String, Object> missingCourse = questionBody(course.id(), competency, "SINGLE", 0);
        missingCourse.put("courseId", null);
        JsonNode required = api.error(INSTRUCTOR, api.post(missingCourse, URL), "CORE-VALIDATION_FAILED");
        assertThat(required.path("errors").get(0).path("field").asText()).isEqualTo("programId");

        Map<String, Object> badCompetency = questionBody(course.id(), 999_999_999L, "SINGLE", 0);
        JsonNode reference = api.error(INSTRUCTOR, api.post(badCompetency, URL), "ASSESSMENT-INVALID_REFERENCE");
        assertThat(reference.path("errors").get(0).path("field").asText()).isEqualTo("competencyId");

        Course other = fixtures.course();
        Map<String, Object> foreignLesson = questionBody(course.id(), competency, "SINGLE", 0);
        foreignLesson.put("lessonId", other.lessonId());
        JsonNode lesson = api.error(INSTRUCTOR, api.post(foreignLesson, URL), "ASSESSMENT-INVALID_REFERENCE");
        assertThat(lesson.path("errors").get(0).path("field").asText()).isEqualTo("lessonId");

        String code = unique("NV");
        Map<String, Object> coded = questionBody(course.id(), competency, "SINGLE", 0);
        coded.put("code", code);
        assertThat(api.ok(INSTRUCTOR, api.post(coded, URL)).path("code").asText()).isEqualTo(code);
        JsonNode duplicate = api.error(INSTRUCTOR, api.post(coded, URL), "ASSESSMENT-DUPLICATE");
        assertThat(duplicate.path("errors").get(0).path("field").asText()).isEqualTo("code");

        api.error(INSTRUCTOR, api.post(questionBody(999_999_999L, competency, "SINGLE", 0), URL),
            "LEARNING-NOT_FOUND");
    }

    @Test
    void learnerCannotManageQuestionBank() {
        api.http(LEARNER, api.post(questionBody(course.id(), competency, "SINGLE", 0), URL), 403,
            "CORE-ACCESS_DENIED");
        api.http(LEARNER, get(URL + "?courseId={c}", course.id()), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void listFiltersPaginatesAndCountsUsage() {
        Course own = fixtures.course();
        long single = fixtures.question(own.id(), competency, "SINGLE", 0);
        long multiple = fixtures.question(own.id(), competency, "MULTIPLE", 0, 1);
        fixtures.quiz(quizBody(own.id()), List.of(single, multiple), false);

        JsonNode page = api.ok(INSTRUCTOR, get(URL + "?courseId={c}&page=0&size=1&sort=id,asc", own.id()));
        assertThat(page.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("items", "page", "size", "totalElements", "totalPages");
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
        JsonNode first = page.path("items").get(0);
        assertThat(first.path("id").asLong()).isEqualTo(single);
        assertThat(first.path("optionCount").asLong()).isEqualTo(4);
        assertThat(first.path("correctCount").asLong()).isEqualTo(1);
        assertThat(first.path("usedInQuizCount").asLong()).isEqualTo(1);
        assertThat(first.path("competencyName").asText()).isNotBlank();
        assertThat(first.has("options")).as("danh sách không trả phương án/đáp án").isFalse();

        JsonNode filtered = api.ok(INSTRUCTOR, get(URL + "?courseId={c}&questionType=MULTIPLE", own.id()));
        assertThat(filtered.path("items").findValuesAsText("id")).containsExactly(String.valueOf(multiple));
        JsonNode search = api.ok(INSTRUCTOR, get(URL + "?courseId={c}&q=Q{id}", own.id(), single));
        assertThat(search.path("items").findValuesAsText("id")).contains(String.valueOf(single));

        api.error(INSTRUCTOR, get(URL + "?courseId={c}&size=101", own.id()), "CORE-VALIDATION_FAILED");
        api.error(INSTRUCTOR, get(URL + "?courseId={c}&sort=content,asc", own.id()), "CORE-VALIDATION_FAILED");
        api.error(INSTRUCTOR, get(URL + "?courseId={c}&difficulty=X", own.id()), "CORE-VALIDATION_FAILED");
    }

    @Test
    void updateMergesOptionsInPlaceAndChecksVersion() {
        JsonNode created = api.ok(INSTRUCTOR, api.post(questionBody(course.id(), competency, "SINGLE", 0), URL));
        long id = created.path("id").asLong();
        long version = created.path("rowVersion").asLong();
        JsonNode keep = created.path("options").get(0);

        Map<String, Object> update = questionBody(course.id(), competency, "SINGLE", 0);
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(option(keep.path("id").asLong(), "Phương án A đã sửa", true));
        options.add(option(null, "Phương án mới", false));
        update.put("options", options);

        api.error(INSTRUCTOR, api.put(update, URL + "/{id}?expectedVersion={v}", id, version + 9),
            "ASSESSMENT-CONFLICT");
        JsonNode updated = api.ok(INSTRUCTOR, api.put(update, URL + "/{id}?expectedVersion={v}", id, version));
        assertThat(updated.path("rowVersion").asLong()).isGreaterThan(version);
        assertThat(updated.path("options")).hasSize(2);
        assertThat(updated.path("options").get(0).path("id").asLong()).isEqualTo(keep.path("id").asLong());
        assertThat(updated.path("options").get(0).path("content").asText()).isEqualTo("Phương án A đã sửa");
        assertThat(updated.path("contentVersion").asInt()).as("chưa phát đề thì không tăng").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM learning_hub.question_options WHERE question_id = ? AND is_deleted
            """, Integer.class, id)).as("phương án bị bỏ được xóa mềm").isEqualTo(3);

        Map<String, Object> foreign = questionBody(course.id(), competency, "SINGLE", 0);
        foreign.put("options", List.of(option(999_999_999L, "X", true), option(null, "Y", false)));
        JsonNode error = api.error(INSTRUCTOR, api.put(foreign, URL + "/{id}?expectedVersion={v}", id,
            updated.path("rowVersion").asLong()), "CORE-VALIDATION_FAILED");
        assertThat(error.path("errors").get(0).path("field").asText()).isEqualTo("options[0].id");

        Map<String, Object> move = questionBody(fixtures.course().id(), competency, "SINGLE", 0);
        api.error(INSTRUCTOR, api.put(move, URL + "/{id}?expectedVersion={v}", id,
            updated.path("rowVersion").asLong()), "CORE-VALIDATION_FAILED");
    }

    @Test
    void bulkStatusAndDeleteRespectQuizUsage() {
        Course own = fixtures.course();
        long a = fixtures.question(own.id(), competency, "SINGLE", 0);
        long b = fixtures.question(own.id(), competency, "SINGLE", 0);
        long free = fixtures.question(own.id(), competency, "SINGLE", 0);
        fixtures.quiz(quizBody(own.id()), List.of(a, b), true);

        JsonNode shortage = api.error(INSTRUCTOR, api.post(Map.of("ids", List.of(a), "status", "INACTIVE"),
            URL + "/bulk-status"), "ASSESSMENT-QUIZ_NOT_READY");
        assertThat(shortage.path("message").asText()).isNotBlank();
        JsonNode changed = api.ok(INSTRUCTOR, api.post(Map.of("ids", List.of(free, free), "status", "INACTIVE"),
            URL + "/bulk-status"));
        assertThat(changed.path("affected").asInt()).isEqualTo(1);
        assertThat(api.ok(INSTRUCTOR, api.post(Map.of("ids", List.of(free), "status", "INACTIVE"),
            URL + "/bulk-status")).path("affected").asInt()).as("đã INACTIVE thì không đổi").isZero();

        JsonNode inUse = api.error(INSTRUCTOR, api.post(Map.of("ids", List.of(a, free)), URL + "/bulk-delete"),
            "ASSESSMENT-QUESTION_IN_USE");
        assertThat(inUse.path("errors").get(0).path("field").asText()).isEqualTo("ids");
        assertThat(api.ok(INSTRUCTOR, api.post(Map.of("ids", List.of(free)), URL + "/bulk-delete"))
            .path("affected").asInt()).isEqualTo(1);
        api.error(INSTRUCTOR, get(URL + "/{id}", free), "ASSESSMENT-NOT_FOUND");
        api.error(INSTRUCTOR, api.post(Map.of("ids", List.of(free)), URL + "/bulk-delete"),
            "ASSESSMENT-INVALID_REFERENCE");
        api.error(INSTRUCTOR, api.post(Map.of("ids", List.of()), URL + "/bulk-delete"), "CORE-VALIDATION_FAILED");
    }

    @Test
    void programBankCrudDoesNotRequireACourseAndKeepsItsScope() {
        long program = jdbc.queryForObject("""
            select program_id from learning_hub.course_revisions where revision_id = ?
            """, Long.class, course.revisionId());
        Map<String, Object> body = questionBody(course.id(), competency, "SINGLE", 0);
        body.remove("courseId");
        body.put("programId", program);
        body.put("code", unique("PROGRAM"));
        JsonNode created = api.ok(ROOT, api.post(body, URL));
        long id = created.path("id").asLong();
        assertThat(created.path("programId").asLong()).isEqualTo(program);
        assertThat(created.path("courseId").isNull()).isTrue();
        JsonNode page = api.ok(ROOT, get(URL + "?programId={p}&q={q}", program, body.get("code")));
        assertThat(page.path("items")).hasSize(1);
        assertThat(page.path("items").get(0).path("correctCount").asLong()).isEqualTo(1);
        api.http(LEARNER, get(URL + "?programId={p}", program), 403, "CORE-ACCESS_DENIED");
        api.http(LEARNER, api.post(body, URL), 403, "CORE-ACCESS_DENIED");
        api.error(ROOT, api.put(body, URL + "/{id}?expectedVersion={v}", id, 99), "ASSESSMENT-CONFLICT");
        JsonNode updated = api.ok(ROOT, api.put(body, URL + "/{id}?expectedVersion={v}", id,
            created.path("rowVersion").asLong()));
        Map<String, Object> moved = new LinkedHashMap<>(body);
        long other = fixtures.course().revisionId();
        moved.put("programId", jdbc.queryForObject("""
            select program_id from learning_hub.course_revisions where revision_id = ?
            """, Long.class, other));
        api.error(ROOT, api.put(moved, URL + "/{id}?expectedVersion={v}", id,
            updated.path("rowVersion").asLong()), "CORE-VALIDATION_FAILED");
        api.ok(ROOT, api.post(Map.of("ids", List.of(id), "status", "INACTIVE"), URL + "/bulk-status"));
        api.ok(ROOT, api.post(Map.of("ids", List.of(id)), URL + "/bulk-delete"));
        api.error(ROOT, get(URL + "/{id}", id), "ASSESSMENT-NOT_FOUND");
    }

    @Test
    void quizAcceptsAnotherCourseInItsProgramAndRejectsAnotherProgram() {
        long program = jdbc.queryForObject("""
            select program_id from learning_hub.course_revisions where revision_id = ?
            """, Long.class, course.revisionId());
        long question = fixtures.question(course.id(), competency, "SINGLE", 0);
        long copied = api.ok(INSTRUCTOR, api.post(Map.of("code", unique("COPY")),
            "/api/v1/courses/{c}/copy", course.id())).path("course").path("id").asLong();
        long quiz = fixtures.quiz(quizBody(copied), List.of(), false);
        JsonNode candidates = api.ok(INSTRUCTOR, get(URL + "?programId={p}&courseId={c}", program, copied));
        assertThat(candidates.path("items").findValues("id").stream().map(JsonNode::asLong).toList())
            .contains(question);
        Map<String, Object> programQuestion = questionBody(course.id(), competency, "SINGLE", 0);
        programQuestion.remove("courseId");
        programQuestion.put("programId", program);
        long shared = api.ok(ROOT, api.post(programQuestion, URL)).path("id").asLong();
        Map<String, Object> pool = Map.of("items", List.of(Map.of("questionId", question, "sortNo", 0),
            Map.of("questionId", shared, "sortNo", 1)));
        JsonNode saved = api.ok(INSTRUCTOR, api.put(pool,
            "/api/v1/quizzes/{id}/questions?expectedVersion={v}", quiz, fixtures.quizVersion(quiz)));
        assertThat(saved.path("pool").get(0).path("questionId").asLong()).isEqualTo(question);
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", saved.path("quiz").path("rowVersion").asLong()),
            "/api/v1/quizzes/{id}/publish", quiz));
        api.error(ROOT, api.post(Map.of("ids", List.of(shared)), URL + "/bulk-delete"),
            "ASSESSMENT-QUESTION_IN_USE");
        programQuestion.put("status", "INACTIVE");
        api.error(ROOT, api.put(programQuestion, URL + "/{id}?expectedVersion={v}", shared,
            api.ok(ROOT, get(URL + "/{id}", shared)).path("rowVersion").asLong()),
            "ASSESSMENT-QUIZ_NOT_READY");
        assertThat(api.ok(ROOT, get(URL + "/{id}", shared)).path("status").asText()).isEqualTo("ACTIVE");
        Course outside = fixtures.course();
        long foreign = fixtures.question(outside.id(), competency, "SINGLE", 0);
        api.error(INSTRUCTOR, api.put(Map.of("items", List.of(Map.of("questionId", foreign, "sortNo", 0))),
            "/api/v1/quizzes/{id}/questions?expectedVersion={v}", quiz, fixtures.quizVersion(quiz)),
            "ASSESSMENT-INVALID_REFERENCE");
        api.error(INSTRUCTOR, get(URL + "?programId={p}&courseId={c}", program, outside.id()),
            "LEARNING-INVALID_REFERENCE");
    }

    private static Map<String, Object> option(Long id, String content, boolean correct) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("id", id);
        option.put("content", content);
        option.put("correct", correct);
        return option;
    }
}
