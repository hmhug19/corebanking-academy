package vn.com.coreacademy.learning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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

/** Khối Mô phỏng luồng: lưu qua cây khóa, đọc lại ở cây và ở học viên, các quy tắc, xuất bản và bản sao. */
class FlowBlockIT extends AssessmentIntegrationTest {
    private static final String COURSE = "/api/v1/courses/{c}";
    private static final String REVISION = "/api/v1/courses/{c}/revisions/{r}";
    private static final String VALIDATION = "CORE-VALIDATION_FAILED";

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

    private static final class Draft {
        final Course course;
        final long programId;
        final long moduleId;
        long revisionId;
        long version;

        Draft(Course course, JsonNode detail) {
            this.course = course;
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
        return new Draft(course, api.ok(INSTRUCTOR, api.post(Map.of(), COURSE + "/draft", course.id())));
    }

    private static Map<String, Object> object(String id, String name, String kind, int x, int z) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", name);
        item.put("kind", kind);
        item.put("x", x);
        item.put("z", z);
        return item;
    }

    private static Map<String, Object> step(String id, String from, String to, String title, String description) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("from", from);
        item.put("to", to);
        item.put("title", title);
        item.put("description", description);
        return item;
    }

    /** Khách hàng → App → Core (tự kiểm tra) → Sổ cái. */
    private static Map<String, Object> sampleFlow() {
        List<Object> objects = new ArrayList<>(List.of(object("kh", "Khách hàng", "customer", -3, 0),
            object("app", "BIDV SmartBanking", "channel", 0, 0), object("core", "Core Banking", "system", 3, 0),
            object("so-cai", "Sổ cái", "database", 3, 3)));
        List<Object> steps = new ArrayList<>(List.of(
            step("s1", "kh", "app", "Nhập lệnh chuyển tiền", "Xác thực Smart OTP."),
            step("s2", "app", "core", "Gửi yêu cầu", null),
            step("s3", "core", null, "Kiểm tra số dư", "Không đủ số dư thì từ chối."),
            step("s4", "core", "so-cai", "Ghi nợ tài khoản", null)));
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("version", 1);
        flow.put("objects", objects);
        flow.put("steps", steps);
        return flow;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> changed(Consumer<Map<String, Object>> change) {
        Map<String, Object> flow = sampleFlow();
        change.accept(flow);
        return flow;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Map<String, Object> flow) {
        return (List<Map<String, Object>>) (List<?>) flow.get("objects");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Map<String, Object> flow) {
        return (List<Map<String, Object>>) (List<?>) flow.get("steps");
    }

    private static Map<String, Object> block(String type, Object flow) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("blockType", type);
        block.put("title", "Chuyển tiền liên ngân hàng");
        block.put("body", "flow".equals(type) ? null : "Nội dung.");
        block.put("bodyFormat", "flow".equals(type) ? null : "markdown");
        block.put("completionMode", "manual");
        block.put("completionThreshold", null);
        block.put("isRequired", true);
        block.put("assets", List.of());
        block.put("steps", List.of());
        if (flow != null) {
            block.put("flow", flow);
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
        body.put("title", "Khóa mô phỏng luồng");
        body.put("summary", "Khóa kiểm thử khối mô phỏng luồng.");
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

    private void refused(Draft draft, Map<String, Object> block, String field) {
        JsonNode body = api.error(INSTRUCTOR, api.put(tree(draft, block), REVISION, draft.course.id(),
            draft.revisionId), VALIDATION);
        assertThat(body.path("errors").get(0).path("field").asText()).as(body.toPrettyString()).isEqualTo(field);
    }

    private static JsonNode firstBlock(JsonNode detail) {
        return detail.path("chapters").get(0).path("lessons").get(0).path("blocks").get(0).path("block");
    }

    private static void assertSample(JsonNode block) {
        assertThat(block.path("blockType").asText()).isEqualTo("flow");
        JsonNode flow = block.path("flow");
        assertThat(flow.path("version").asInt()).isEqualTo(1);
        assertThat(flow.path("objects")).hasSize(4);
        assertThat(flow.path("objects").get(1).path("name").asText()).isEqualTo("BIDV SmartBanking");
        assertThat(flow.path("objects").get(3).path("kind").asText()).isEqualTo("database");
        assertThat(flow.path("objects").get(3).path("z").asInt()).isEqualTo(3);
        assertThat(flow.path("steps")).hasSize(4);
        assertThat(flow.path("steps").get(0).path("description").asText()).isEqualTo("Xác thực Smart OTP.");
        assertThat(flow.path("steps").get(2).path("to").isNull()).isTrue();
        assertThat(flow.path("steps").get(3).path("from").asText()).isEqualTo("core");
    }

    private void publish(Draft draft) {
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", draft.version), REVISION + "/publish",
            draft.course.id(), draft.revisionId));
    }

    @Test
    void aFlowComesBackInTheTreeAndInTheLearnersCourse() {
        Draft draft = draft();
        assertSample(firstBlock(save(draft, block("flow", sampleFlow()))));
        assertSample(firstBlock(api.ok(INSTRUCTOR, get(COURSE + "?revisionId={r}", draft.course.id(),
            draft.revisionId))));

        publish(draft);
        assign(draft, LEARNER);
        JsonNode course = api.ok(LEARNER, get("/api/v1/me/learning/courses/{c}", draft.course.id()));
        assertSample(firstBlock(course.path("content")));
    }

    @Test
    void namesAndTitlesAreTrimmed() {
        Draft draft = draft();
        JsonNode flow = firstBlock(save(draft, block("flow", changed(f -> {
            objects(f).get(0).put("name", "  Khách hàng  ");
            steps(f).get(0).put("title", "  Nhập lệnh  ");
        })))).path("flow");
        assertThat(flow.path("objects").get(0).path("name").asText()).isEqualTo("Khách hàng");
        assertThat(flow.path("steps").get(0).path("title").asText()).isEqualTo("Nhập lệnh");
    }

    @Test
    void refusesAFlowThatBreaksItsRules() {
        Draft draft = draft();
        refused(draft, block("flow", null), "flow");
        refused(draft, block("text", sampleFlow()), "flow");
        refused(draft, block("flow", changed(f -> f.put("version", 2))), "flow.version");
        refused(draft, block("flow", changed(f -> objects(f).get(1).put("kind", "robot"))), "flow.objects[1].kind");
        refused(draft, block("flow", changed(f -> objects(f).get(1).put("id", "kh"))), "flow.objects[1].id");
        refused(draft, block("flow", changed(f -> objects(f).get(1).put("id", "Có dấu"))), "flow.objects[1].id");
        refused(draft, block("flow", changed(f -> objects(f).get(0).put("name", "   "))), "flow.objects[0].name");
        refused(draft, block("flow", changed(f -> objects(f).get(0).put("name", "a".repeat(61)))),
            "flow.objects[0].name");
        refused(draft, block("flow", changed(f -> objects(f).get(0).put("x", 41))), "flow.objects[0].x");
        refused(draft, block("flow", changed(f -> objects(f).get(0).put("z", null))), "flow.objects[0].z");
        refused(draft, block("flow", changed(f -> {
            objects(f).get(1).put("x", -3);
            objects(f).get(1).put("z", 0);
        })), "flow.objects[1].x");
        refused(draft, block("flow", changed(f -> steps(f).get(1).put("id", "s1"))), "flow.steps[1].id");
        refused(draft, block("flow", changed(f -> steps(f).get(1).put("from", "khong-co"))), "flow.steps[1].from");
        refused(draft, block("flow", changed(f -> steps(f).get(1).put("to", "khong-co"))), "flow.steps[1].to");
        refused(draft, block("flow", changed(f -> steps(f).get(1).put("to", "app"))), "flow.steps[1].to");
        refused(draft, block("flow", changed(f -> steps(f).get(1).put("title", ""))), "flow.steps[1].title");
        refused(draft, block("flow", changed(f -> steps(f).get(1).put("title", "t".repeat(256)))),
            "flow.steps[1].title");
        refused(draft, block("flow", changed(f -> steps(f).get(1).put("description", "d".repeat(2001)))),
            "flow.steps[1].description");
    }

    @Test
    void refusesMoreThanThirtyObjectsAndMoreThanAHundredSteps() {
        Draft draft = draft();
        refused(draft, block("flow", changed(f -> {
            for (int i = 0; i < 27; i++) {
                objects(f).add(object("o" + i, "Đối tượng " + i, "system", i - 20, -10));
            }
        })), "flow.objects");
        refused(draft, block("flow", changed(f -> {
            for (int i = 0; i < 97; i++) {
                steps(f).add(step("t" + i, "kh", "app", "Bước " + i, null));
            }
        })), "flow.steps");

        JsonNode flow = firstBlock(save(draft, block("flow", changed(f -> {
            for (int i = 0; i < 26; i++) {
                objects(f).add(object("o" + i, "Đối tượng " + i, "system", i - 20, -10));
            }
            for (int i = 0; i < 96; i++) {
                steps(f).add(step("t" + i, "kh", "app", "Bước " + i, null));
            }
        })))).path("flow");
        assertThat(flow.path("objects")).hasSize(30);
        assertThat(flow.path("steps")).hasSize(100);
    }

    @Test
    void anEmptyFlowSavesButCannotBePublished() {
        Draft draft = draft();
        JsonNode block = firstBlock(save(draft, block("flow", changed(f -> steps(f).clear()))));
        assertThat(block.path("flow").path("steps")).isEmpty();
        api.error(INSTRUCTOR, api.post(Map.of("expectedVersion", draft.version), REVISION + "/publish",
            draft.course.id(), draft.revisionId), "LEARNING-NOT_PUBLISHABLE");

        save(draft, block("flow", sampleFlow()));
        publish(draft);
    }

    @Test
    void aNewRevisionKeepsTheFlow() {
        Draft draft = draft();
        save(draft, block("flow", sampleFlow()));
        publish(draft);
        JsonNode next = api.ok(INSTRUCTOR, api.post(Map.of(), COURSE + "/draft", draft.course.id()));
        assertThat(next.path("revision").path("id").asLong()).isNotEqualTo(draft.revisionId);
        assertSample(firstBlock(next));
    }

    @Test
    void otherBlocksCarryNoFlow() {
        Draft draft = draft();
        JsonNode block = firstBlock(save(draft, block("text", null)));
        assertThat(block.path("flow").isNull() || block.path("flow").isMissingNode()).isTrue();
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
