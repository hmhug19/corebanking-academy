package vn.com.coreacademy.learning;

import java.util.HashMap;
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
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.REPORT_VIEWER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.unique;

/** POST /courses kèm {@code draft}: ghi khóa, revision 1 và cây nội dung trong một transaction. */
class CourseCreateWithDraftIT extends AssessmentIntegrationTest {
    private static final String COURSES = "/api/v1/courses";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private long org;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        org = new AssessmentFixtures(api, jdbc).orgId();
    }

    private Map<String, Object> create(String code, String title, Map<String, Object> draft) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownerOrgId", org);
        body.put("ownerTeamId", null);
        body.put("code", code);
        body.put("title", title);
        if (draft != null) {
            body.put("draft", draft);
        }
        return body;
    }

    private Map<String, Object> block(String blockType) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("blockType", blockType);
        block.put("title", "Giới thiệu");
        block.put("body", "Nội dung bài học.");
        block.put("bodyFormat", "markdown");
        block.put("completionMode", "manual");
        block.put("completionThreshold", null);
        block.put("isRequired", true);
        block.put("assets", List.of());
        block.put("steps", List.of());
        return block;
    }

    private Map<String, Object> draft(String title, Map<String, Object> block) {
        Map<String, Object> lesson = new LinkedHashMap<>();
        lesson.put("title", "Bài 1");
        lesson.put("lessonType", "content");
        lesson.put("estimatedDurationSeconds", 300);
        lesson.put("isRequired", true);
        lesson.put("quizId", null);
        lesson.put("blocks", List.of(block));
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("title", title);
        draft.put("summary", "Tóm tắt khóa.");
        draft.put("level", "basic");
        draft.put("defaultRequirement", "optional");
        draft.put("objectives", List.of("Mục tiêu 1", "Mục tiêu 2"));
        draft.put("chapters", List.of(Map.of("title", "Chương 1", "lessons", List.of(lesson))));
        draft.put("rewardPoints", 25);
        return draft;
    }

    private int courseCount(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.courses WHERE code = ?", Integer.class, code);
    }

    @Test
    void createWithDraftReturnsTheWholeTree() {
        String code = unique("CW");

        JsonNode created = api.ok(INSTRUCTOR, api.post(create(code, "Tiêu đề ngoài",
            draft("Tiêu đề nháp", block("text"))), COURSES));

        JsonNode revision = created.path("revision");
        assertThat(revision.path("revisionNo").asInt()).isEqualTo(1);
        assertThat(revision.path("state").asText()).isEqualTo("draft");
        assertThat(revision.path("title").asText()).isEqualTo("Tiêu đề nháp");
        assertThat(revision.path("summary").asText()).isEqualTo("Tóm tắt khóa.");
        assertThat(revision.path("defaultRequirement").asText()).isEqualTo("optional");
        assertThat(created.path("objectives")).hasSize(2);
        assertThat(created.path("completionRule").path("rewardPoints").asInt()).isEqualTo(25);
        JsonNode chapters = created.path("chapters");
        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).path("chapter").path("title").asText()).isEqualTo("Chương 1");
        JsonNode lessons = chapters.get(0).path("lessons");
        assertThat(lessons).hasSize(1);
        assertThat(lessons.get(0).path("blocks")).hasSize(1);

        // Đọc lại phải khớp, và version trả về dùng được ngay cho lần lưu nháp kế tiếp.
        long courseId = created.path("course").path("id").asLong();
        JsonNode read = api.ok(INSTRUCTOR, ApiClient.get(COURSES + "/{id}", courseId));
        assertThat(read.path("revision").path("rowVersion").asLong()).isEqualTo(revision.path("rowVersion").asLong());
        assertThat(read.path("chapters")).hasSize(1);
        assertThat(read.path("objectives")).hasSize(2);
        Map<String, Object> resave = draft("Tiêu đề nháp", block("text"));
        resave.put("expectedVersion", revision.path("rowVersion").asLong());
        api.ok(INSTRUCTOR, api.put(resave, COURSES + "/{c}/revisions/{r}", courseId, revision.path("id").asLong()));
    }

    @Test
    void createFallsBackToOuterTitleWhenDraftHasNone() {
        Map<String, Object> draft = draft(null, block("text"));

        JsonNode created = api.ok(INSTRUCTOR, api.post(create(unique("CW"), "Tiêu đề ngoài", draft), COURSES));

        assertThat(created.path("revision").path("title").asText()).isEqualTo("Tiêu đề ngoài");
    }

    @Test
    void createWithoutDraftIsUnchanged() {
        JsonNode created = api.ok(INSTRUCTOR, api.post(create(unique("CW"), "Khóa trống", null), COURSES));

        JsonNode revision = created.path("revision");
        assertThat(revision.path("revisionNo").asInt()).isEqualTo(1);
        assertThat(revision.path("title").asText()).isEqualTo("Khóa trống");
        assertThat(created.path("chapters")).isEmpty();
        assertThat(created.path("objectives")).isEmpty();
        assertThat(created.path("completionRule").path("rewardPoints").asInt()).isZero();
    }

    @Test
    void duplicateCodeIsRejectedEvenWithDraft() {
        String code = unique("CW");
        api.ok(INSTRUCTOR, api.post(create(code, "Khóa 1", null), COURSES));

        api.error(INSTRUCTOR, api.post(create(code, "Khóa 2", draft("Khóa 2", block("text"))), COURSES),
            "LEARNING-DUPLICATE");
        assertThat(courseCount(code)).isEqualTo(1);
    }

    @Test
    void invalidBlockLeavesNoCourse() {
        String code = unique("CW");
        Map<String, Object> bad = block("text");
        bad.put("completionMode", "pdf_end");

        api.error(INSTRUCTOR, api.post(create(code, "Khóa lỗi", draft("Khóa lỗi", bad)), COURSES),
            "CORE-VALIDATION_FAILED");

        assertThat(courseCount(code)).isZero();
        // Mã vẫn còn trống nên tạo lại với nội dung hợp lệ phải thành công.
        api.ok(INSTRUCTOR, api.post(create(code, "Khóa lỗi", draft("Khóa lỗi", block("text"))), COURSES));
    }

    @Test
    void createKeepsFormattedTextAndRemovesUntrustedMarkup() {
        Map<String, Object> formatted = block("text");
        formatted.put("bodyFormat", "html");
        formatted.put("body", "<h2>Quy trình</h2><p><strong><u>Nội dung</u></strong>"
            + "<img src='https://example.org/remote.png'><a href='javascript:alert(1)'>Mốc</a>"
            + "<script>alert(1)</script></p>");

        JsonNode created = api.ok(INSTRUCTOR, api.post(
            create(unique("CW"), "Soạn bài", draft("Soạn bài", formatted)), COURSES));
        JsonNode saved = created.path("chapters").get(0).path("lessons").get(0).path("blocks").get(0).path("block");
        assertThat(saved.path("bodyFormat").asText()).isEqualTo("html");
        assertThat(saved.path("body").asText()).contains("<h2>Quy trình</h2>", "<strong><u>Nội dung</u></strong>", "Mốc")
            .doesNotContain("<script", "<img", "javascript:", "alert(1)");
    }

    @Test
    void createInfersTheAuthorsTeamForATeamScopedInstructor() {
        Long team = jdbc.queryForObject(
            "SELECT team_id FROM learning_hub.users WHERE user_id = ?", Long.class, REPORT_VIEWER);
        Long role = jdbc.queryForObject(
            "SELECT role_id FROM learning_hub.roles WHERE code = 'INSTRUCTOR'", Long.class);
        jdbc.update("""
            INSERT INTO learning_hub.user_roles(user_id, role_id, scope, org_id, team_id, granted_by)
            VALUES (?, ?, 'TEAM', ?, ?, '000001')
            """, REPORT_VIEWER, role, org, team);
        try {
            JsonNode created = api.ok(REPORT_VIEWER, api.post(create(unique("CW"), "Khóa theo nhóm", null), COURSES));
            assertThat(created.path("course").path("ownerTeamId").asLong()).isEqualTo(team);
        } finally {
            jdbc.update("""
                DELETE FROM learning_hub.user_roles WHERE user_id = ? AND role_id = ? AND scope = 'TEAM' AND team_id = ?
                """, REPORT_VIEWER, role, team);
        }
    }

    @Test
    void callerWithoutManageRightsIsRefused() {
        String code = unique("CW");

        api.http(LEARNER, api.post(create(code, "Khóa", draft("Khóa", block("text"))), COURSES), 403,
            "CORE-ACCESS_DENIED");

        assertThat(courseCount(code)).isZero();
    }
}
