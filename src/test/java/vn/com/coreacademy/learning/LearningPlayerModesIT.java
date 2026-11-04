package vn.com.coreacademy.learning;

import java.math.BigDecimal;
import java.util.ArrayList;
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
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;

/** Mốc video và transcript của khối video: lưu qua cây khóa, đọc lại ở cây và ở học viên, giới hạn và bản sao. */
class LearningPlayerModesIT extends AssessmentIntegrationTest {
    private static final String COURSE = "/api/v1/courses/{c}";
    private static final String REVISION = "/api/v1/courses/{c}/revisions/{r}";
    private static final String VALIDATION = "CORE-VALIDATION_FAILED";
    private static final String CHAPTERS = "videoChapters";
    private static final String TRANSCRIPT = "transcript";

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

    /** Bản nháp của một khóa có sẵn tài nguyên video READY dài 30 giây; version theo dõi rowVersion mới nhất. */
    private static final class Draft {
        final Course course;
        final long assetId;
        final long programId;
        final long moduleId;
        long revisionId;
        long version;

        Draft(Course course, long assetId, JsonNode detail) {
            this.course = course;
            this.assetId = assetId;
            this.programId = detail.path("revision").path("programId").asLong();
            this.moduleId = detail.path("revision").path("moduleId").asLong();
            track(detail);
        }

        void track(JsonNode detail) {
            revisionId = detail.path("revision").path("id").asLong();
            version = detail.path("revision").path("rowVersion").asLong();
        }
    }

    private Draft draft() {
        Course course = fixtures.course();
        long asset = jdbc.queryForObject("""
            insert into learning_hub.assets (owner_org_id, uploaded_by, storage_type, original_name, mime_type,
                size_bytes, duration_seconds, status, created_by, updated_by)
            values (?, ?, 's3', 'video-kiem-thu', 'video/mp4', 1000, 30.000, 'ready', ?, ?) returning asset_id
            """, Long.class, course.ownerOrgId(), INSTRUCTOR, INSTRUCTOR, INSTRUCTOR);
        return new Draft(course, asset, api.ok(INSTRUCTOR, api.post(Map.of(), COURSE + "/draft", course.id())));
    }

    private static Map<String, Object> chapter(Object start, String title) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("startSeconds", start);
        item.put("title", title);
        return item;
    }

    private static Map<String, Object> line(Object start, String text) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("startSeconds", start);
        item.put("text", text);
        return item;
    }

    private static List<Map<String, Object>> sampleChapters() {
        return List.of(chapter(0, "Mở đầu"), chapter(10, "Khái niệm"), chapter(20.5, "Tổng kết"));
    }

    private static List<Map<String, Object>> sampleLines() {
        return List.of(line(0, "Xin chào các bạn."), line(5, "Hôm nay học về lõi ngân hàng."),
            line(12.25, "Đây là phần khái niệm."), line(30, "Cảm ơn đã theo dõi."));
    }

    private Map<String, Object> block(Draft draft, String type, Object chapters, Object lines) {
        boolean video = "video".equals(type);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("blockType", type);
        block.put("title", video ? "Video bài học" : "Văn bản");
        block.put("body", video ? null : "Nội dung.");
        block.put("bodyFormat", video ? null : "markdown");
        block.put("completionMode", video ? "video_coverage" : "manual");
        block.put("completionThreshold", video ? 80 : null);
        block.put("isRequired", true);
        block.put("assets", video ? List.of(Map.of("assetId", draft.assetId, "usage", "primary")) : List.of());
        block.put("steps", List.of());
        if (chapters != null) {
            block.put(CHAPTERS, chapters);
        }
        if (lines != null) {
            block.put(TRANSCRIPT, lines);
        }
        return block;
    }

    private Map<String, Object> tree(Draft draft, Map<String, Object> block) {
        Map<String, Object> lesson = new LinkedHashMap<>();
        lesson.put("title", "Bài 1");
        lesson.put("lessonType", "content");
        lesson.put("estimatedDurationSeconds", 300);
        lesson.put("isRequired", true);
        lesson.put("blocks", List.of(block));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", draft.version);
        body.put("programId", draft.programId);
        body.put("moduleId", draft.moduleId);
        body.put("title", "Khóa mốc video");
        body.put("summary", "Khóa kiểm thử mốc và transcript.");
        body.put("level", "basic");
        body.put("defaultRequirement", "optional");
        body.put("coverAssetId", null);
        body.put("coverIcon", null);
        body.put("coverColor", null);
        body.put("objectives", List.of("Mục tiêu kiểm thử"));
        body.put("chapters", List.of(Map.of("title", "Chương 1", "lessons", List.of(lesson))));
        body.put("rewardPoints", 0);
        return body;
    }

    private JsonNode save(Draft draft, Map<String, Object> block) {
        JsonNode saved = api.ok(INSTRUCTOR, api.put(tree(draft, block), REVISION, draft.course.id(),
            draft.revisionId));
        draft.track(saved);
        return saved;
    }

    private JsonNode refused(Draft draft, Map<String, Object> block, String field) {
        JsonNode body = api.error(INSTRUCTOR, api.put(tree(draft, block), REVISION, draft.course.id(),
            draft.revisionId), VALIDATION);
        assertThat(body.path("errors").get(0).path("field").asText()).as(body.toPrettyString()).isEqualTo(field);
        return body;
    }

    private static JsonNode firstBlock(JsonNode detail) {
        return detail.path("chapters").get(0).path("lessons").get(0).path("blocks").get(0).path("block");
    }

    private static void assertSample(JsonNode block) {
        JsonNode chapters = block.path(CHAPTERS);
        assertThat(chapters).hasSize(3);
        assertThat(chapters.get(0).path("title").asText()).isEqualTo("Mở đầu");
        assertThat(chapters.get(2).path("startSeconds").decimalValue()).isEqualByComparingTo("20.5");
        JsonNode lines = block.path(TRANSCRIPT);
        assertThat(lines).hasSize(4);
        assertThat(lines.get(1).path("text").asText()).isEqualTo("Hôm nay học về lõi ngân hàng.");
        assertThat(lines.get(2).path("startSeconds").decimalValue()).isEqualByComparingTo("12.25");
    }

    private boolean columnsAreNull(long blockId) {
        return jdbc.queryForObject("select video_chapters is null and transcript is null from "
            + "learning_hub.lesson_blocks where block_id = ?", Boolean.class, blockId);
    }

    @Test
    void savedListsComeBackInTheTreeAndInTheLearnersCourseAndEnrollment() {
        Draft draft = draft();
        JsonNode saved = save(draft, block(draft, "video", sampleChapters(), sampleLines()));
        assertSample(firstBlock(saved));
        assertSample(firstBlock(api.ok(INSTRUCTOR, get(COURSE + "?revisionId={r}", draft.course.id(),
            draft.revisionId))));

        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", draft.version), REVISION + "/publish",
            draft.course.id(), draft.revisionId));
        assign(draft, LEARNER);

        JsonNode course = api.ok(LEARNER, get("/api/v1/me/learning/courses/{c}", draft.course.id()));
        assertSample(firstBlock(course.path("content")));
        long enrollment = course.path("card").path("enrollmentId").asLong();
        assertThat(enrollment).isPositive();
        JsonNode detail = api.ok(LEARNER, get("/api/v1/me/learning/enrollments/{e}", enrollment));
        assertSample(firstBlock(detail.path("content")));
    }

    @Test
    void trimsTheText() {
        Draft draft = draft();
        JsonNode block = firstBlock(save(draft, block(draft, "video",
            List.of(chapter(0, "  Mở đầu  ")), List.of(line(30, "  Hết.  ")))));
        assertThat(block.path(CHAPTERS).get(0).path("title").asText()).isEqualTo("Mở đầu");
        assertThat(block.path(TRANSCRIPT).get(0).path("text").asText()).isEqualTo("Hết.");
        assertThat(block.path(TRANSCRIPT).get(0).path("startSeconds").decimalValue()).isEqualByComparingTo("30");
    }

    @Test
    void refusesMoreThanFiftyChaptersAndMoreThanTwoThousandLines() {
        Draft draft = draft();
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (int i = 0; i <= 50; i++) {
            chapters.add(chapter(i * 0.5, "Mốc " + i));
        }
        refused(draft, block(draft, "video", chapters, null), CHAPTERS);
        chapters.remove(50);
        save(draft, block(draft, "video", chapters, null));

        List<Map<String, Object>> lines = new ArrayList<>();
        for (int i = 0; i <= 2000; i++) {
            lines.add(line(i / 100.0, "Đoạn " + i));
        }
        refused(draft, block(draft, "video", null, lines), TRANSCRIPT);
        lines.remove(2000);
        JsonNode block = firstBlock(save(draft, block(draft, "video", null, lines)));
        assertThat(block.path(TRANSCRIPT)).hasSize(2000);
        assertThat(block.path(CHAPTERS)).isEmpty();
    }

    @Test
    void refusesBlankAndOverlongText() {
        Draft draft = draft();
        refused(draft, block(draft, "video", List.of(chapter(0, "   ")), null), CHAPTERS);
        refused(draft, block(draft, "video", List.of(chapter(0, "a".repeat(121))), null), CHAPTERS);
        refused(draft, block(draft, "video", null, List.of(line(0, ""))), TRANSCRIPT);
        refused(draft, block(draft, "video", null, List.of(line(0, "b".repeat(1001)))), TRANSCRIPT);
        List<Object> withNull = new ArrayList<>();
        withNull.add(null);
        refused(draft, block(draft, "video", withNull, null), CHAPTERS);

        JsonNode block = firstBlock(save(draft, block(draft, "video", List.of(chapter(0, "a".repeat(120))),
            List.of(line(0, "b".repeat(1000))))));
        assertThat(block.path(CHAPTERS).get(0).path("title").asText()).hasSize(120);
        assertThat(block.path(TRANSCRIPT).get(0).path("text").asText()).hasSize(1000);
    }

    @Test
    void refusesNegativeMissingAndNonIncreasingStarts() {
        Draft draft = draft();
        refused(draft, block(draft, "video", List.of(chapter(-1, "Âm")), null), CHAPTERS);
        refused(draft, block(draft, "video", List.of(chapter(null, "Thiếu")), null), CHAPTERS);
        refused(draft, block(draft, "video", List.of(chapter(5, "A"), chapter(5, "B")), null), CHAPTERS);
        refused(draft, block(draft, "video", null, List.of(line(8, "A"), line(3, "B"))), TRANSCRIPT);
        refused(draft, block(draft, "video", null, List.of(line(0, "A"), line(10, "B"), line(9.5, "C"))),
            TRANSCRIPT);
    }

    @Test
    void refusesStartsBeyondTheKnownDuration() {
        Draft draft = draft();
        refused(draft, block(draft, "video", List.of(chapter(0, "A"), chapter(new BigDecimal("30.001"), "B")), null),
            CHAPTERS);
        refused(draft, block(draft, "video", null, List.of(line(31, "Quá dài"))), TRANSCRIPT);
        JsonNode block = firstBlock(save(draft, block(draft, "video", List.of(chapter(30, "Đúng cuối")), null)));
        assertThat(block.path(CHAPTERS)).hasSize(1);
    }

    @Test
    void refusesListsOnABlockThatIsNotAVideo() {
        Draft draft = draft();
        refused(draft, block(draft, "text", List.of(chapter(0, "A")), null), CHAPTERS);
        refused(draft, block(draft, "text", null, List.of(line(0, "A"))), TRANSCRIPT);
        JsonNode block = firstBlock(save(draft, block(draft, "text", List.of(), List.of())));
        assertThat(block.path(CHAPTERS)).isEmpty();
    }

    @Test
    void aNewRevisionKeepsTheLists() {
        Draft draft = draft();
        save(draft, block(draft, "video", sampleChapters(), sampleLines()));
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", draft.version), REVISION + "/publish",
            draft.course.id(), draft.revisionId));
        JsonNode next = api.ok(INSTRUCTOR, api.post(Map.of(), COURSE + "/draft", draft.course.id()));
        assertThat(next.path("revision").path("id").asLong()).isNotEqualTo(draft.revisionId);
        assertSample(firstBlock(next));
    }

    @Test
    void savingWithoutTheListsClearsThemAndAnEmptyListIsStoredAsNull() {
        Draft draft = draft();
        JsonNode saved = save(draft, block(draft, "video", sampleChapters(), sampleLines()));
        long first = firstBlock(saved).path("id").asLong();
        assertThat(columnsAreNull(first)).isFalse();

        JsonNode cleared = firstBlock(save(draft, block(draft, "video", null, null)));
        assertThat(cleared.path(CHAPTERS)).isEmpty();
        assertThat(cleared.path(TRANSCRIPT)).isEmpty();
        assertThat(columnsAreNull(cleared.path("id").asLong())).isTrue();

        save(draft, block(draft, "video", sampleChapters(), sampleLines()));
        JsonNode empty = firstBlock(save(draft, block(draft, "video", List.of(), List.of())));
        assertThat(empty.path(CHAPTERS)).isEmpty();
        assertThat(columnsAreNull(empty.path("id").asLong())).isTrue();
    }

    @Test
    void assignmentHistoryKeepsOrgScopeWithoutGrantingCrossOrgAssignment() {
        Course course = fixtures.course(LEARNER);
        long otherOrg = jdbc.queryForObject("""
            insert into learning_hub.orgs (org_type, code, name)
            values ('UNIT', ?, 'Đơn vị kiểm thử lịch sử giao học') returning org_id
            """, Long.class, AssessmentFixtures.unique("ASSIGN-ORG"));
        String list = COURSE + "/assignments?assigningOrgId={org}&page=0&size=10";

        JsonNode empty = api.ok(ADMIN, get(list, course.id(), otherOrg));
        assertThat(empty.path("items")).isEmpty();
        assertThat(empty.path("totalElements").asLong()).isZero();
        api.http(INSTRUCTOR, get(list, course.id(), otherOrg), 403, "CORE-ACCESS_DENIED");
        api.http(LEARNER, get(list, course.id(), course.ownerOrgId()), 403, "CORE-ACCESS_DENIED");

        JsonNode foreign = api.ok(ADMIN, api.post(Map.of("ownerOrgId", otherOrg,
            "code", AssessmentFixtures.unique("FOREIGN"), "title", "Khóa khác phạm vi giảng viên"), "/api/v1/courses"));
        api.http(INSTRUCTOR, get(list, foreign.path("course").path("id").asLong(), course.ownerOrgId()),
            403, "CORE-ACCESS_DENIED");

        Map<String, Object> request = new HashMap<>();
        request.put("revisionId", course.revisionId());
        request.put("assigningOrgId", otherOrg);
        request.put("title", "Không được phép giao chéo đơn vị khi chưa có share");
        request.put("startAt", "2020-01-01T00:00:00Z");
        request.put("requirement", "mandatory");
        request.put("enrollmentMode", "continue");
        request.put("reminderEnabled", false);
        request.put("reminderDaysBefore", 0);
        request.put("idempotencyKey", AssessmentFixtures.unique("no-share"));
        request.put("targets", List.of(Map.of("targetType", "user", "userId", LEARNER,
            "includeDescendants", false)));
        api.http(ADMIN, api.post(request, COURSE + "/assignments", course.id()), 403, "CORE-ACCESS_DENIED");
        assertThat(jdbc.queryForObject("""
            select count(*) from learning_hub.course_assignments where course_id = ? and assigning_org_id = ?
            """, Long.class, course.id(), otherOrg)).isZero();

        jdbc.update("UPDATE learning_hub.courses SET lifecycle_status = 'paused' WHERE course_id = ?", course.id());
        JsonNode history = api.ok(ADMIN, get(list, course.id(), course.ownerOrgId()));
        assertThat(history.path("items")).hasSize(1);
        assertThat(history.path("totalElements").asLong()).isEqualTo(1);
    }

    private void assign(Draft draft, String learner) {
        Map<String, Object> target = new HashMap<>();
        target.put("targetType", "user");
        target.put("userId", learner);
        target.put("includeDescendants", false);
        Map<String, Object> assign = new HashMap<>();
        assign.put("revisionId", draft.revisionId);
        assign.put("assigningOrgId", draft.course.ownerOrgId());
        assign.put("title", "Đợt kiểm thử");
        assign.put("startAt", "2020-01-01T00:00:00Z");
        assign.put("dueAt", "2099-12-31T16:59:59Z");
        assign.put("requirement", "mandatory");
        assign.put("enrollmentMode", "continue");
        assign.put("reminderEnabled", false);
        assign.put("reminderDaysBefore", 0);
        assign.put("idempotencyKey", AssessmentFixtures.unique("assign"));
        assign.put("targets", List.of(target));
        api.ok(INSTRUCTOR, api.post(assign, COURSE + "/assignments", draft.course.id()));
    }
}
