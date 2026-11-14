package vn.com.coreacademy.assessment;

import java.nio.charset.StandardCharsets;
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
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.quizBody;

/** Thẻ khóa học của học viên: tên lĩnh vực/chương trình/phân hệ và cờ failed lấy từ bài thi cuối khóa. */
class CatalogCardIT extends AssessmentIntegrationTest {
    private static final String LEARNING = "/api/v1/me/learning";

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

    @Test
    void cardsCarryTheFieldProgrammeAndModuleNames() {
        Course course = fixtures.course(LEARNER);
        Map<String, Object> names = jdbc.queryForMap("""
            SELECT f.field_id, f.name AS field_name, p.name AS program_name, m.name AS module_name
            FROM learning_hub.course_revisions r
            JOIN learning_hub.training_programs p ON p.program_id = r.program_id
            JOIN learning_hub.training_fields f ON f.field_id = p.field_id
            JOIN learning_hub.business_modules m ON m.module_id = r.module_id
            WHERE r.revision_id = ?
            """, course.revisionId());

        for (String list : List.of("/courses", "/catalog")) {
            JsonNode card = card(LEARNER, list, course);
            assertThat(card.path("fieldId").asLong()).isEqualTo(((Number) names.get("field_id")).longValue());
            assertThat(card.path("fieldName").asText()).isEqualTo(names.get("field_name"));
            assertThat(card.path("programName").asText()).isEqualTo(names.get("program_name"));
            assertThat(card.path("moduleName").asText()).isEqualTo(names.get("module_name"));
        }
    }

    @Test
    void aMissingProgrammeOrModuleGivesNullNames() {
        Course noModule = fixtures.course(LEARNER);
        jdbc.update("UPDATE learning_hub.course_revisions SET module_id = NULL WHERE revision_id = ?",
            noModule.revisionId());
        Course noProgramme = fixtures.course(LEARNER);
        jdbc.update("UPDATE learning_hub.course_revisions SET program_id = NULL, module_id = NULL WHERE revision_id = ?",
            noProgramme.revisionId());

        JsonNode withoutModule = card(LEARNER, "/courses", noModule);
        assertThat(withoutModule.path("programName").isTextual()).isTrue();
        assertThat(withoutModule.path("fieldName").isTextual()).isTrue();
        assertThat(withoutModule.path("moduleName").isNull()).isTrue();

        JsonNode withoutProgramme = card(LEARNER, "/courses", noProgramme);
        assertThat(withoutProgramme.path("fieldId").isNull()).isTrue();
        assertThat(withoutProgramme.path("fieldName").isNull()).isTrue();
        assertThat(withoutProgramme.path("programName").isNull()).isTrue();
        assertThat(withoutProgramme.path("moduleName").isNull()).isTrue();
    }

    @Test
    void failedIsTrueAfterAFailedFinalExamAndFalseAfterALaterPass() {
        Course course = fixtures.course(LEARNER, LEARNER_2);
        long exam = exam(course, "FINAL_EXAM");
        assertThat(failed(LEARNER, course)).as("chưa thi").isFalse();

        attempt(LEARNER, exam, false);

        assertThat(failed(LEARNER, course)).as("trượt").isTrue();
        assertThat(card(LEARNER, "/catalog", course).path("failed").asBoolean()).isTrue();
        assertThat(failed(LEARNER_2, course)).as("học viên khác chưa thi").isFalse();

        attempt(LEARNER, exam, true);

        assertThat(failed(LEARNER, course)).as("đạt ở lượt sau").isFalse();
        assertThat(card(LEARNER, "/catalog", course).path("failed").asBoolean()).isFalse();
    }

    @Test
    void failedIsFalseForACourseWithoutAFinalExam() {
        Course course = fixtures.course(LEARNER);
        long practice = exam(course, "PRACTICE");
        attempt(LEARNER, practice, false);

        assertThat(failed(LEARNER, course)).as("trượt đề luyện tập không tính").isFalse();
        assertThat(failed(LEARNER, fixtures.course(LEARNER))).as("khóa không có bài thi").isFalse();
    }

    @Test
    void failedIsFalseWhileTheOnlyAttemptIsRunning() {
        Course course = fixtures.course(LEARNER);
        long exam = exam(course, "FINAL_EXAM");
        api.ok(LEARNER, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", exam));

        assertThat(failed(LEARNER, course)).isFalse();
    }

    @Test
    void theInstructorListStillWorksAndNeverFlagsFailed() {
        Course course = fixtures.course(LEARNER);
        attempt(LEARNER, exam(course, "FINAL_EXAM"), false);

        JsonNode page = api.ok(INSTRUCTOR, get("/api/v1/instructor/courses?size=100&sort=id,desc"));

        JsonNode card = null;
        for (JsonNode row : page.path("items")) {
            card = row.path("courseId").asLong() == course.id() ? row : card;
        }
        assertThat(card).as("khóa nằm trong danh sách giảng viên").isNotNull();
        assertThat(card.path("failed").asBoolean()).isFalse();
        assertThat(card.path("fieldName").isTextual()).isTrue();
        assertThat(card.path("programName").isTextual()).isTrue();
        assertThat(card.path("moduleName").isTextual()).isTrue();
    }

    @Test
    void theCsvExportKeepsItsColumnsAndAppendsTheBestScoreAtTheEnd() throws Exception {
        fixtures.course(LEARNER);

        String csv = api.raw(LEARNER, get(LEARNING + "/progress/export")).getContentAsString(StandardCharsets.UTF_8);

        assertThat(csv.lines().findFirst().orElseThrow()).isEqualTo("﻿Mã khóa,Tên khóa,Lần học,Trạng thái,"
            + "Tiến độ,Bắt đầu,Truy cập gần nhất,Hoàn thành,Hạn,Trạng thái hạn,Điểm cao nhất");
    }

    private long exam(Course course, String type) {
        long question = fixtures.question(course.id(), competency, "SINGLE", 0);
        Map<String, Object> body = quizBody(course.id());
        body.put("quizType", type);
        body.put("questionCount", 1);
        return fixtures.quiz(body, List.of(question), true);
    }

    @Test
    void unfinishedCoursesComeBeforeCompletedCoursesBeforePagination() {
        String marker = AssessmentFixtures.unique("PRIORITY");
        Course done = fixtures.course(LEARNER);
        Course untouched = fixtures.course(LEARNER);
        Course ongoing = fixtures.course(LEARNER);
        for (var entry : Map.of(done, "A", untouched, "B", ongoing, "C").entrySet()) {
            jdbc.update("update learning_hub.course_revisions set title = ? where revision_id = ?",
                marker + entry.getValue(), entry.getKey().revisionId());
        }
        jdbc.update("update learning_hub.course_enrollments set status = 'completed', progress_percent = 100 "
            + "where course_id = ?", done.id());
        jdbc.update("update learning_hub.course_enrollments set status = 'in_progress', progress_percent = 100 "
            + "where course_id = ?", ongoing.id());
        for (String list : List.of("/catalog", "/courses?assigned=true", "/courses?requirement=mandatory")) {
            String path = LEARNING + list + (list.contains("?") ? "&" : "?") + "q=" + marker
                + "&size=1&sort=title,asc&page=";
            assertThat(api.ok(LEARNER, get(path + "0")).path("items").get(0).path("courseId").asLong())
                .isEqualTo(untouched.id());
            assertThat(api.ok(LEARNER, get(path + "1")).path("items").get(0).path("courseId").asLong())
                .isEqualTo(ongoing.id());
            assertThat(api.ok(LEARNER, get(path + "2")).path("items").get(0).path("courseId").asLong())
                .isEqualTo(done.id());
        }
    }

    @Test
    void neverOpenedCoursesSortAfterAccessedCoursesInBothDirectionsBeforePagination() {
        String marker = AssessmentFixtures.unique("ACCESS");
        Course never = fixtures.course(LEARNER);
        Course older = fixtures.course(LEARNER);
        Course newer = fixtures.course(LEARNER);
        for (Course item : List.of(never, older, newer)) {
            jdbc.update("update learning_hub.course_revisions set title = ? where revision_id = ?",
                marker + item.id(), item.revisionId());
        }
        jdbc.update("update learning_hub.course_enrollments set last_access_at = null where course_id = ?", never.id());
        jdbc.update("update learning_hub.course_enrollments set last_access_at = '2026-01-01T00:00:00Z' where course_id = ?", older.id());
        jdbc.update("update learning_hub.course_enrollments set last_access_at = '2026-02-01T00:00:00Z' where course_id = ?", newer.id());
        for (String direction : List.of("asc", "desc")) {
            List<Course> expected = "asc".equals(direction) ? List.of(older, newer, never) : List.of(newer, older, never);
            for (int page = 0; page < expected.size(); page++) {
                JsonNode rows = api.ok(LEARNER, get(LEARNING + "/courses?q=" + marker
                    + "&size=1&sort=lastAccessAt," + direction + "&page=" + page));
                assertThat(rows.path("totalElements").asInt()).isEqualTo(3);
                assertThat(rows.path("items").get(0).path("courseId").asLong()).isEqualTo(expected.get(page).id());
            }
        }
    }

    @Test
    void enrolledCoursesSupportTheUnclassifiedModuleFilter() {
        String marker = AssessmentFixtures.unique("NO-MODULE");
        Course classified = fixtures.course(LEARNER);
        Course unclassified = fixtures.course(LEARNER);
        for (Course item : List.of(classified, unclassified)) {
            jdbc.update("update learning_hub.course_revisions set title = ? where revision_id = ?",
                marker + item.id(), item.revisionId());
        }
        jdbc.update("update learning_hub.course_revisions set module_id = null where revision_id = ?", unclassified.revisionId());
        JsonNode rows = api.ok(LEARNER, get(LEARNING + "/courses?q=" + marker + "&withoutModule=true"));
        assertThat(rows.path("totalElements").asInt()).isEqualTo(1);
        assertThat(rows.path("items").get(0).path("courseId").asLong()).isEqualTo(unclassified.id());
    }

    /** Một lượt thi một câu: chọn đúng (A) khi pass, bỏ trống khi không; nộp ngay. */
    private void attempt(String user, long quizId, boolean pass) {
        JsonNode session = api.ok(user, api.post(Map.of(), "/api/v1/me/quizzes/{id}/attempts", quizId));
        long attemptId = session.path("attemptId").asLong();
        if (pass) {
            JsonNode question = session.path("questions").get(0);
            api.ok(user, api.patch(Map.of("selectedOptionIds", List.of(question.path("options").get(0).path("id")
                    .asLong())), "/api/v1/me/quiz-attempts/{a}/questions/{q}", attemptId,
                question.path("attemptQuestionId").asLong()));
        }
        api.ok(user, api.post(Map.of(), "/api/v1/me/quiz-attempts/{id}/submit", attemptId));
    }

    private boolean failed(String user, Course course) {
        return card(user, "/courses", course).path("failed").asBoolean();
    }

    private JsonNode card(String user, String list, Course course) {
        JsonNode page = api.ok(user, get(LEARNING + list + "?size=100&sort=id,desc"));
        for (JsonNode row : page.path("items")) {
            if (row.path("courseId").asLong() == course.id()) {
                return row;
            }
        }
        throw new AssertionError("Không có khóa " + course.id() + " trong " + list);
    }
}
