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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private vn.com.coreacademy.storage.service.ObjectStorage scormStorage;

    @Autowired
    private vn.com.coreacademy.scorm.service.ScormPackageReader scormReader;

    @Test
    void scormRejectsUnsafePathsExternalEntitiesAndWrongVersions() throws Exception {
        String manifest = """
            <manifest xmlns="http://www.imsproject.org/xsd/imscp_rootv1p1p2"
              xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_rootv1p2">
              <metadata><schemaversion>1.2</schemaversion></metadata>
              <resources><resource adlcp:scormtype="sco" href="index.html"/></resources>
            </manifest>
            """;
        for (Map<String, String> entries : List.of(
            Map.of("imsmanifest.xml", manifest, "../escape.js", "bad", "index.html", "<html>valid</html>"),
            Map.of("imsmanifest.xml", "<!DOCTYPE manifest [<!ENTITY unsafe SYSTEM 'file:///no-such-file'>]>" + manifest,
                "index.html", "<html>valid</html>"),
            Map.of("imsmanifest.xml", manifest.replace("1.2</", "2004</"), "index.html", "<html>valid</html>"))) {
            var zipped = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(zipped)) {
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            var file = new org.springframework.mock.web.MockMultipartFile("file", "invalid.zip", "application/zip", zipped.toByteArray());
            assertThatThrownBy(() -> scormReader.read(file)).isInstanceOf(vn.com.coreacademy.common.exception.BusinessException.class);
        }
    }

    @Test
    void scormImportRuntimeResumeAndScopeArePersistedOnTheIsolatedConnector() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("imsmanifest.xml"));
            zip.write("""
                <manifest xmlns="http://www.imsproject.org/xsd/imscp_rootv1p1p2"
                  xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_rootv1p2">
                  <metadata><schemaversion>1.2</schemaversion></metadata>
                  <organizations><organization><title>SCORM kiểm thử</title></organization></organizations>
                  <resources><resource identifier="sco" adlcp:scormtype="sco" href="index.html"/></resources>
                </manifest>
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("index.html"));
            zip.write("<!doctype html><html><body>SCORM</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var upload = new org.springframework.mock.web.MockMultipartFile("file", "test-scorm.zip", "application/zip", bytes.toByteArray());
        var imported = api.ok(INSTRUCTOR, MockMvcRequestBuilders
            .multipart("/api/v1/scorm/packages").file(upload).param("ownerOrgId", Long.toString(org)));
        long packageId = imported.path("id").asLong();
        assertThat(imported.path("published").asBoolean()).isFalse();
        assertThat(imported.path("fileCount").asInt()).isEqualTo(2);
        api.http(LEARNER, ApiClient.get("/api/v1/scorm/packages/{id}", packageId), 403, "CORE-ACCESS_DENIED");
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", 0), "/api/v1/scorm/packages/{id}/publish", packageId));
        api.ok(LEARNER, ApiClient.get("/api/v1/scorm/packages/{id}", packageId));
        JsonNode learnerLibrary = api.ok(LEARNER, ApiClient.get("/api/v1/scorm/packages").param("q", "SCORM"));
        assertThat(learnerLibrary.path("items").findValues("id").stream().map(JsonNode::asLong).toList())
            .contains(packageId);
        api.http(LEARNER, MockMvcRequestBuilders
            .multipart("/api/v1/scorm/packages").file(upload).param("ownerOrgId", Long.toString(org)), 403, "CORE-ACCESS_DENIED");

        long av = jdbc.queryForObject("SELECT auth_ver FROM learning_hub.users WHERE user_id = ?", Long.class, LEARNER);
        var launchRequest = api.post(Map.of("preview", false), "/api/v1/scorm/packages/{id}/launch", packageId)
            .with(SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(LEARNER).claim("av", av)));
        var launched = json.readTree(mvc.perform(launchRequest).andReturn().getResponse().getContentAsString());
        assertThat(launched.path("status").asText()).as(launched.toPrettyString()).isEqualTo("SUCCESS");
        var playerUri = java.net.URI.create(launched.path("data").path("launchUrl").asText());
        String playerOrigin = playerUri.getScheme() + "://" + playerUri.getRawAuthority();
        String token = playerUri.getPath().split("/")[2];
        String runtime = "/scorm-player/" + token + "/runtime";
        // Nginx terminates HTTPS; the connector still sees HTTP with the public player Origin header.
        var preflight = mvc.perform(MockMvcRequestBuilders.options(runtime)
            .header("Origin", playerOrigin)
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type")
            .with(request -> { request.setLocalPort(8020); return request; })).andReturn().getResponse();
        assertThat(preflight.getStatus()).isEqualTo(200);
        assertThat(preflight.getHeader("Access-Control-Allow-Origin")).isEqualTo(playerOrigin);
        assertThat(preflight.getHeader("Access-Control-Allow-Credentials")).isNull();
        var foreignOrigin = mvc.perform(MockMvcRequestBuilders.options(runtime)
            .header("Origin", "https://untrusted.example.test")
            .header("Access-Control-Request-Method", "POST")
            .with(request -> { request.setLocalPort(8020); return request; })).andReturn().getResponse();
        assertThat(foreignOrigin.getStatus()).isEqualTo(403);
        assertThat(foreignOrigin.getHeader("Access-Control-Allow-Origin")).isNull();
        var values = Map.of("cmi.core.lesson_status", "passed", "cmi.core.score.raw", "80",
            "cmi.core.score.max", "100", "cmi.core.lesson_location", "page-4",
            "cmi.suspend_data", "{\"page\":4}", "cmi.core.exit", "suspend", "cmi.core.session_time", "0000:01:12.50");
        var save = Map.of("sequence", 1, "values", values, "finished", false);
        for (int retry = 0; retry < 2; retry++) {
            var response = mvc.perform(api.post(save, runtime).header("Origin", playerOrigin)
                .with(request -> { request.setLocalPort(8020); return request; }))
                .andReturn().getResponse();
            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(playerOrigin);
            var receipt = json.readTree(response.getContentAsString());
            assertThat(receipt.path("status").asText()).as(receipt.toPrettyString()).isEqualTo("SUCCESS");
            assertThat(receipt.path("data").path("progress").path("scoreRaw").asInt()).isEqualTo(80);
        }
        assertThat(api.ok(LEARNER, ApiClient.get("/api/v1/scorm/packages/{id}/progress", packageId))
            .path("lessonLocation").asText()).isEqualTo("page-4");
        var resume = json.readTree(mvc.perform(api.post(Map.of("preview", false),
            "/api/v1/scorm/packages/{id}/launch", packageId).with(
                SecurityMockMvcRequestPostProcessors.jwt()
                    .jwt(jwt -> jwt.subject(LEARNER).claim("av", av))))
            .andReturn().getResponse().getContentAsString());
        String resumed = java.net.URI.create(resume.path("data").path("launchUrl").asText()).getPath();
        var html = mvc.perform(ApiClient.get(resumed).with(request -> {request.setLocalPort(8020);return request;}))
            .andReturn().getResponse();
        assertThat(html.getContentAsString()).contains("page-4", "cmi.suspend_data", "resume");
        assertThat(mvc.perform(ApiClient.get(resumed)).andReturn().getResponse().getStatus()).isIn(401, 403);
        assertThat(mvc.perform(ApiClient.get("/api/v1/scorm/packages").with(request -> {
            request.setLocalPort(8020);return request;})).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(api.post(save, runtime).with(request -> {request.setLocalPort(8020);return request;}))
            .andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT total_seconds FROM learning_hub.scorm_registrations WHERE package_id = ? AND user_id = ?",
            java.math.BigDecimal.class, packageId, LEARNER)).isEqualByComparingTo("72.50");
    }

    @Test
    void scormLessonsImportWithTheCourseAndKeepAuthoritativeProgressPerEnrollmentAndLesson() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipOutputStream(bytes)) {
            Map<String, String> files = Map.of("imsmanifest.xml", """
                <manifest xmlns="http://www.imsproject.org/xsd/imscp_rootv1p1p2"
                  xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_rootv1p2">
                  <metadata><schemaversion>1.2</schemaversion></metadata>
                  <resources><resource identifier="sco" adlcp:scormtype="sco" href="index.html"/></resources>
                </manifest>
                """, "index.html", "<!doctype html><html><body>SCORM course lesson</body></html>");
            for (var file : files.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        var upload = new org.springframework.mock.web.MockMultipartFile("file", "lesson.zip",
            "application/zip", bytes.toByteArray());
        long packageId = api.ok(INSTRUCTOR, MockMvcRequestBuilders.multipart("/api/v1/scorm/packages")
            .file(upload).param("ownerOrgId", Long.toString(org))).path("id").asLong();
        var fixtures = new AssessmentFixtures(api, jdbc);
        var reference = fixtures.course();
        Long program = jdbc.queryForObject("SELECT program_id FROM learning_hub.course_revisions WHERE revision_id = ?",
            Long.class, reference.revisionId());
        Map<String, Object> lesson = Map.of("title", "SCORM trong khóa", "lessonType", "scorm",
            "estimatedDurationSeconds", 300, "isRequired", true, "blocks", List.of(), "scormPackageId", packageId);
        Map<String, Object> content = draft("Khóa học SCORM", block("text"));
        content.put("programId", program);
        content.put("chapters", List.of(Map.of("title", "Nội dung tương tác", "lessons", List.of(lesson, lesson))));
        var created = api.ok(INSTRUCTOR, api.post(create(unique("SCORM"), "Khóa học SCORM", content), COURSES));
        long courseId = created.path("course").path("id").asLong();
        long revision = created.path("revision").path("id").asLong();
        long first = created.path("chapters").get(0).path("lessons").get(0).path("lesson").path("id").asLong();
        long second = created.path("chapters").get(0).path("lessons").get(1).path("lesson").path("id").asLong();
        assertThat(created.path("chapters").get(0).path("lessons").get(0).path("lesson")
            .path("scormPackageId").asLong()).isEqualTo(packageId);
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", created.path("revision").path("rowVersion").asLong()),
            "/api/v1/courses/{c}/revisions/{r}/publish", courseId, revision));
        assertThat(api.ok(INSTRUCTOR, ApiClient.get("/api/v1/scorm/packages/{id}", packageId))
            .path("published").asBoolean()).isTrue();
        fixtures.assign(courseId, revision, LEARNER);
        long enrollment = jdbc.queryForObject("SELECT enrollment_id FROM learning_hub.course_enrollments"
            + " WHERE course_id = ? AND user_id = ?", Long.class, courseId, LEARNER);
        var context = Map.of("preview", false, "enrollmentId", enrollment, "lessonId", first);
        api.http(AssessmentFixtures.LEARNER_2, api.post(context, "/api/v1/scorm/packages/{id}/launch", packageId),
            403, "CORE-ACCESS_DENIED");
        api.http(LEARNER, api.post(Map.of("preview", false, "enrollmentId", enrollment,
            "lessonId", reference.lessonId()), "/api/v1/scorm/packages/{id}/launch", packageId),
            403, "CORE-ACCESS_DENIED");
        String firstPath = courseScormLaunch(packageId, enrollment, first);
        var values = new HashMap<String, String>(Map.of("cmi.core.lesson_status", "incomplete",
            "cmi.core.score.raw", "100", "cmi.core.lesson_location", "page-4", "cmi.core.exit", "suspend"));
        var incomplete = mvc.perform(api.post(Map.of("sequence", 1, "values", values, "finished", false),
            firstPath.replace("/launch", "/runtime")).with(request -> {
                request.setLocalPort(8020); return request;
            })).andReturn().getResponse();
        assertThat(incomplete.getStatus()).isEqualTo(200);
        assertThat(api.ok(LEARNER, ApiClient.get("/api/v1/me/learning/enrollments/{id}", enrollment))
            .path("enrollment").path("progressPercent").decimalValue()).isEqualByComparingTo("0");
        values.put("cmi.core.lesson_status", "passed");
        assertThat(mvc.perform(api.post(Map.of("sequence", 2, "values", values, "finished", true),
            firstPath.replace("/launch", "/runtime")).with(request -> {
                request.setLocalPort(8020); return request;
            })).andReturn().getResponse().getStatus()).isEqualTo(200);
        var halfway = api.ok(LEARNER, ApiClient.get("/api/v1/me/learning/enrollments/{id}", enrollment));
        assertThat(halfway.path("enrollment").path("progressPercent").decimalValue()).isEqualByComparingTo("50");
        assertThat(halfway.path("enrollment").path("status").asText()).isEqualTo("in_progress");
        assertThat(api.ok(LEARNER, ApiClient.get("/api/v1/scorm/packages/{id}/progress", packageId))
            .path("lessonStatus").asText()).isEqualTo("not attempted");
        String resumed = courseScormLaunch(packageId, enrollment, first);
        String html = mvc.perform(ApiClient.get(resumed).with(request -> {
            request.setLocalPort(8020); return request;
        })).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("page-4", "resume", "passed");
        String secondPath = courseScormLaunch(packageId, enrollment, second);
        assertThat(mvc.perform(ApiClient.get(secondPath).with(request -> {
            request.setLocalPort(8020); return request;
        })).andReturn().getResponse().getContentAsString()).doesNotContain("page-4");
        assertThat(mvc.perform(api.post(Map.of("sequence", 1, "values", values, "finished", true),
            secondPath.replace("/launch", "/runtime")).with(request -> {
                request.setLocalPort(8020); return request;
            })).andReturn().getResponse().getStatus()).isEqualTo(200);
        var finished = api.ok(LEARNER, ApiClient.get("/api/v1/me/learning/enrollments/{id}", enrollment));
        assertThat(finished.path("enrollment").path("status").asText()).isEqualTo("completed");
        assertThat(finished.path("enrollment").path("progressPercent").decimalValue()).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.scorm_registrations"
            + " WHERE package_id = ? AND enrollment_id = ?", Integer.class, packageId, enrollment)).isEqualTo(2);
    }

    private String courseScormLaunch(long packageId, long enrollmentId, long lessonId) throws Exception {
        long version = jdbc.queryForObject("SELECT auth_ver FROM learning_hub.users WHERE user_id = ?",
            Long.class, LEARNER);
        var response = mvc.perform(api.post(Map.of("preview", false, "enrollmentId", enrollmentId,
            "lessonId", lessonId), "/api/v1/scorm/packages/{id}/launch", packageId)
            .with(SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt.subject(LEARNER).claim("av", version))))
            .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        var data = json.readTree(response.getContentAsString());
        assertThat(data.path("status").asText()).as(data.toPrettyString()).isEqualTo("SUCCESS");
        return java.net.URI.create(data.path("data").path("launchUrl").asText()).getPath();
    }

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
    void createKeepsAnInlineImageAndTableInTheTextBlock() {
        Long asset = jdbc.queryForObject("""
            INSERT INTO learning_hub.assets(owner_org_id, uploaded_by, storage_type, original_name, mime_type,
                size_bytes, status, created_by, updated_by)
            VALUES (?, ?, 'external', 'anh.png', 'image/png', 1000, 'ready', ?, ?) RETURNING asset_id
            """, Long.class, org, INSTRUCTOR, INSTRUCTOR, INSTRUCTOR);
        Map<String, Object> formatted = block("text");
        formatted.put("bodyFormat", "html");
        formatted.put("body", "<table><tbody><tr><td colspan='2'>Số liệu</td></tr></tbody></table>"
            + "<img data-asset-id='" + asset + "' alt='Sơ đồ' onerror='alert(1)'>");
        formatted.put("assets", List.of(Map.of("assetId", asset, "usage", "image")));
        JsonNode created = api.ok(INSTRUCTOR, api.post(create(unique("CW"), "Ảnh trong bài", draft("Ảnh trong bài", formatted)), COURSES));
        JsonNode saved = created.path("chapters").get(0).path("lessons").get(0).path("blocks").get(0);
        assertThat(saved.path("block").path("body").asText()).contains("<table>", "colspan=\"2\"", "data-asset-id=\"" + asset + "\"")
            .doesNotContain("onerror");
        assertThat(saved.path("assets")).hasSize(1);

        formatted.put("assets", List.of());
        api.error(INSTRUCTOR, api.post(create(unique("CW"), "Thiếu tham chiếu", draft("Thiếu tham chiếu", formatted)), COURSES),
            "LEARNING-ASSET_INVALID");
    }

    @Test
    void callerWithoutManageRightsIsRefused() {
        String code = unique("CW");

        api.http(LEARNER, api.post(create(code, "Khóa", draft("Khóa", block("text"))), COURSES), 403,
            "CORE-ACCESS_DENIED");

        assertThat(courseCount(code)).isZero();
    }

    @Test
    void courseAndSelectedExamAreSavedAtomicallyAndEnforceProgramAndVersions() {
        var fixtures = new AssessmentFixtures(api, jdbc);
        var bankCourse = fixtures.course();
        long question = fixtures.question(bankCourse.id(), fixtures.competency(), "SINGLE", 0);
        Long program = jdbc.queryForObject(
            "SELECT program_id FROM learning_hub.course_revisions WHERE revision_id = ?",
            Long.class, bankCourse.revisionId());
        Map<String, Object> content = draft("Khóa kèm bài thi", block("text"));
        content.put("programId", program);
        Map<String, Object> quiz = AssessmentFixtures.quizBody(bankCourse.id());
        quiz.remove("courseId");
        quiz.put("quizType", "FINAL_EXAM");
        quiz.put("questionCount", 1);
        Map<String, Object> pool = Map.of("items", List.of(Map.of("questionId", question, "sortNo", 0)));
        String code = unique("ATOMIC");
        JsonNode saved = api.ok(INSTRUCTOR, api.post(
            Map.of("course", create(code, "Khóa kèm bài thi", content), "quiz", quiz, "pool", pool),
            "/api/v1/course-exams"));
        long courseId = saved.path("course").path("id").asLong();
        long quizId = saved.path("finalQuizId").asLong();
        JsonNode exam = api.ok(INSTRUCTOR, ApiClient.get("/api/v1/quizzes/{id}", quizId));
        assertThat(quizId).isPositive();
        assertThat(exam.path("quiz").path("courseId").asLong()).isEqualTo(courseId);
        assertThat(exam.path("pool")).hasSize(1);
        assertThat(exam.path("pool").get(0).path("questionId").asLong()).isEqualTo(question);
        assertThat(saved.path("completionRule").path("finalQuizId").asLong()).isEqualTo(quizId);

        var foreignCourse = fixtures.course();
        long foreignQuestion = fixtures.question(foreignCourse.id(), fixtures.competency(), "SINGLE", 0);
        String rejectedCode = unique("ROLLBACK");
        Integer before = jdbc.queryForObject("SELECT count(*) FROM learning_hub.quizzes", Integer.class);
        api.error(INSTRUCTOR, api.post(Map.of("course", create(rejectedCode, "Không được tạo", content),
            "quiz", quiz, "pool", Map.of("items", List.of(Map.of("questionId", foreignQuestion, "sortNo", 0)))),
            "/api/v1/course-exams"), "ASSESSMENT-INVALID_REFERENCE");
        assertThat(courseCount(rejectedCode)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.quizzes", Integer.class)).isEqualTo(before);

        Map<String, Object> changed = new HashMap<>(content);
        changed.put("expectedVersion", saved.path("revision").path("rowVersion").asLong());
        changed.put("finalQuizId", quizId);
        changed.put("title", "Khóa đã chỉnh và vẫn giữ bộ câu hỏi");
        Map<String, Object> update = Map.of("courseId", courseId,
            "revisionId", saved.path("revision").path("id").asLong(), "draft", changed,
            "quizId", quizId, "expectedQuizVersion", exam.path("quiz").path("rowVersion").asLong(),
            "quiz", quiz, "pool", pool);
        JsonNode edited = api.ok(INSTRUCTOR, api.post(update, "/api/v1/course-exams"));
        assertThat(edited.path("finalQuizId").asLong()).isEqualTo(quizId);
        api.error(INSTRUCTOR, api.post(update, "/api/v1/course-exams"), "LEARNING-CONFLICT");
        api.http(LEARNER, api.post(update, "/api/v1/course-exams"), 403, "CORE-ACCESS_DENIED");
    }
}
