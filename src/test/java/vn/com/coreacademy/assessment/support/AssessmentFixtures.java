package vn.com.coreacademy.assessment.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;

import static vn.com.coreacademy.assessment.support.ApiClient.get;

/**
 * Dựng dữ liệu bằng API thật (Learning + Assessment) với tài khoản seed của learning_hub_identity.sql, để test đi
 * đúng luồng FE thay vì chèn SQL bỏ qua kiểm tra nghiệp vụ.
 */
public final class AssessmentFixtures {
    public static final String ROOT = "000001";
    public static final String ADMIN = "000002";
    public static final String LEARNER = "000004";
    public static final String INSTRUCTOR = "000005";
    public static final String REPORT_VIEWER = "000006";
    public static final String LEARNER_2 = "000007";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final ApiClient api;
    private final JdbcTemplate jdbc;

    public AssessmentFixtures(ApiClient api, JdbcTemplate jdbc) {
        this.api = api;
        this.jdbc = jdbc;
    }

    public record Course(long id, long revisionId, long lessonId, long ownerOrgId) {
    }

    public static String unique(String prefix) {
        return prefix + "-" + SEQUENCE.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    public long orgId() {
        return jdbc.queryForObject("SELECT org_id FROM learning_hub.orgs WHERE code = 'BIDV110B803'", Long.class);
    }

    /** Khóa đã xuất bản (1 bài bắt buộc, 1 khối text) và giao cho các học viên chỉ định. */
    public Course course(String... learners) {
        long org = orgId();
        long field = api.ok(INSTRUCTOR, api.post(catalog(org, null, unique("F")), "/api/v1/training-catalog/fields"))
            .path("id").asLong();
        long program = api.ok(INSTRUCTOR, api.post(catalog(org, field, unique("P")),
            "/api/v1/training-catalog/programs")).path("id").asLong();
        long module = api.ok(INSTRUCTOR, api.post(catalog(org, field, unique("M")),
            "/api/v1/training-catalog/modules")).path("id").asLong();

        Map<String, Object> create = new HashMap<>();
        create.put("ownerOrgId", org);
        create.put("ownerTeamId", null);
        create.put("code", unique("C"));
        create.put("title", "Khóa kiểm thử Quiz");
        JsonNode created = api.ok(INSTRUCTOR, api.post(create, "/api/v1/courses"));
        long courseId = created.path("course").path("id").asLong();
        long revisionId = created.path("revision").path("id").asLong();

        JsonNode saved = api.ok(INSTRUCTOR, api.put(draft(created.path("revision").path("rowVersion").asLong(),
            program, module), "/api/v1/courses/{c}/revisions/{r}", courseId, revisionId));
        long lessonId = saved.path("chapters").get(0).path("lessons").get(0).path("lesson").path("id").asLong();
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", saved.path("revision").path("rowVersion").asLong()),
            "/api/v1/courses/{c}/revisions/{r}/publish", courseId, revisionId));

        if (learners.length > 0) {
            assign(courseId, revisionId, learners);
        }
        return new Course(courseId, revisionId, lessonId, org);
    }

    /** Giao phiên bản đã xuất bản của khóa cho các học viên chỉ định (mỗi người một lần học trên phiên bản đó). */
    public void assign(long courseId, long revisionId, String... learners) {
        List<Map<String, Object>> targets = new ArrayList<>();
        for (String learner : learners) {
            Map<String, Object> target = new HashMap<>();
            target.put("targetType", "user");
            target.put("userId", learner);
            target.put("includeDescendants", false);
            targets.add(target);
        }
        Map<String, Object> assign = new HashMap<>();
        assign.put("revisionId", revisionId);
        assign.put("assigningOrgId", orgId());
        assign.put("title", "Đợt kiểm thử");
        assign.put("startAt", "2020-01-01T00:00:00Z");
        assign.put("dueAt", "2099-12-31T16:59:59Z");
        assign.put("requirement", "mandatory");
        assign.put("enrollmentMode", "continue");
        assign.put("reminderEnabled", false);
        assign.put("reminderDaysBefore", 0);
        assign.put("idempotencyKey", unique("assign"));
        assign.put("targets", targets);
        api.ok(INSTRUCTOR, api.post(assign, "/api/v1/courses/{c}/assignments", courseId));
    }

    public long competency() {
        Map<String, Object> body = new HashMap<>();
        body.put("code", unique("COMP"));
        body.put("name", "Năng lực kiểm thử");
        body.put("description", null);
        body.put("parentId", null);
        body.put("sortNo", 0);
        body.put("status", "ACTIVE");
        return api.ok(ADMIN, api.post(body, "/api/v1/competencies")).path("id").asLong();
    }

    /** Body câu hỏi: 4 phương án A-D, đáp án đúng theo chỉ số. */
    public static Map<String, Object> questionBody(long courseId, long competencyId, String type, int... correct) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("id", null);
            option.put("content", "Phương án " + (char) ('A' + i));
            boolean right = false;
            for (int index : correct) {
                right |= index == i;
            }
            option.put("correct", right);
            options.add(option);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("courseId", courseId);
        body.put("lessonId", null);
        body.put("competencyId", competencyId);
        body.put("code", null);
        body.put("questionType", type);
        body.put("content", "Câu hỏi kiểm thử " + unique("Q"));
        body.put("explanation", "Giải thích đáp án");
        body.put("difficulty", "MEDIUM");
        body.put("points", 1);
        body.put("status", "ACTIVE");
        body.put("options", options);
        return body;
    }

    public long question(long courseId, long competencyId, String type, int... correct) {
        return api.ok(INSTRUCTOR, api.post(questionBody(courseId, competencyId, type, correct),
            "/api/v1/questions")).path("id").asLong();
    }

    public static Map<String, Object> quizBody(long courseId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("courseId", courseId);
        body.put("title", "Bài kiểm tra " + unique("QZ"));
        body.put("description", "Quy chế thi");
        body.put("quizType", "PRACTICE");
        body.put("selectionMode", "FIXED");
        body.put("questionCount", 2);
        body.put("shuffleQuestions", false);
        body.put("shuffleOptions", false);
        body.put("timeLimitMinutes", 15);
        body.put("openAt", null);
        body.put("closeAt", null);
        body.put("graceSeconds", 5);
        body.put("passPercent", 50);
        body.put("scoringMode", "ALL_OR_NOTHING");
        body.put("scoreMethod", "BEST");
        body.put("reviewPolicy", "AFTER_SUBMIT");
        body.put("maxAttempts", 3);
        body.put("retakeWaitHours", 0);
        body.put("allowRetakeAfterPass", false);
        body.put("requireLessonsCompleted", false);
        return body;
    }

    /** Tạo đề, gán pool và (tùy chọn) xuất bản; trả quizId. */
    public long quiz(Map<String, Object> body, List<Long> questionIds, boolean publish) {
        JsonNode created = api.ok(INSTRUCTOR, api.post(body, "/api/v1/quizzes"));
        long quizId = created.path("quiz").path("id").asLong();
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < questionIds.size(); i++) {
            items.add(Map.of("questionId", questionIds.get(i), "sortNo", i));
        }
        JsonNode pooled = api.ok(INSTRUCTOR, api.put(Map.of("items", items),
            "/api/v1/quizzes/{id}/questions?expectedVersion={v}", quizId,
            created.path("quiz").path("rowVersion").asLong()));
        if (publish) {
            api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", pooled.path("quiz").path("rowVersion").asLong()),
                "/api/v1/quizzes/{id}/publish", quizId));
        }
        return quizId;
    }

    public long quizVersion(long quizId) {
        return api.ok(INSTRUCTOR, get("/api/v1/quizzes/{id}", quizId)).path("quiz").path("rowVersion").asLong();
    }

    private static Map<String, Object> catalog(long org, Long field, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("ownerOrgId", org);
        body.put("fieldId", field);
        body.put("code", code);
        body.put("name", "Danh mục " + code);
        body.put("description", null);
        body.put("status", "published");
        body.put("sortNo", 0);
        return body;
    }

    private static Map<String, Object> draft(long version, long program, long module) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("blockType", "text");
        block.put("title", "Giới thiệu");
        block.put("body", "Nội dung bài học.");
        block.put("bodyFormat", "markdown");
        block.put("completionMode", "manual");
        block.put("completionThreshold", null);
        block.put("isRequired", true);
        block.put("assets", List.of());
        block.put("steps", List.of());
        Map<String, Object> lesson = new LinkedHashMap<>();
        lesson.put("title", "Bài 1");
        lesson.put("lessonType", "content");
        lesson.put("estimatedDurationSeconds", 300);
        lesson.put("isRequired", true);
        lesson.put("blocks", List.of(block));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", version);
        body.put("programId", program);
        body.put("moduleId", module);
        body.put("title", "Khóa kiểm thử Quiz");
        body.put("summary", "Khóa dùng cho integration test.");
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
}
