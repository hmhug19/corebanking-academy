package vn.com.coreacademy.assistant.external;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;

/**
 * {@code propose_step} of the Trợ lý ngoài and its sessions in the drafting flow: the proposal lands where the worker's
 * would, the worker and the chat leave the session alone, "Đề xuất lại" waits for the Trợ lý ngoài again, and what the
 * author confirmed (their edits included) is what the next step builds on.
 */
class ExternalProposeIT extends ExternalAssistantIT {
    private static final String SESSIONS = "/api/v1/assistant/drafting-sessions";
    private static final String PROPOSE = ExternalAssistantTools.PROPOSE;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CourseDraftingRepository drafting;
    @Autowired
    private TransactionTemplate transaction;

    private ApiClient api;
    private McpClient mcp;
    private long ownOrg;
    private long competency;
    private String token;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        mcp = new McpClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        ownOrg = fixtures.orgId();
        competency = fixtures.competency();
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM learning_hub.external_assistant_connection");
        jdbc.update("DELETE FROM learning_hub.course_drafting_steps");
        jdbc.update("DELETE FROM learning_hub.course_drafting_sessions");
        token = api.ok(INSTRUCTOR, api.post(Map.of("name", "Claude Code", "validDays", 30),
            ExternalConnectionIT.CONNECTIONS)).path("token").asText();
    }

    private long start() {
        return mcp.ok(token, ExternalAssistantTools.START, Map.of("source_name", "quy-trinh.pdf")).data()
            .path("sessionId").asLong();
    }

    private McpClient.ToolResult propose(long id, int step, Object proposal) {
        return mcp.call(token, PROPOSE, Map.of("session_id", id, "step", step, "proposal", proposal));
    }

    private static Map<String, Object> basics() {
        Map<String, Object> basics = new HashMap<>();
        basics.put("title", "Mở tài khoản thanh toán");
        basics.put("summary", "Quy trình mở tài khoản cho khách hàng cá nhân.");
        basics.put("objectives", List.of("Kiểm tra được hồ sơ"));
        basics.put("level", "basic");
        basics.put("defaultRequirement", "mandatory");
        return basics;
    }

    private static Map<String, Object> outline() {
        return Map.of("chapters", List.of(Map.of("title", "Chuẩn bị", "lessons", List.of(
            Map.of("title", "Điều kiện", "description", "Ai được mở tài khoản.", "section", "Phần 1"),
            Map.of("title", "Hồ sơ", "description", "Giấy tờ khách hàng nộp.", "section", "Phần 2")))));
    }

    private static Map<String, Object> rules(Object competencyId) {
        Map<String, Object> rules = new HashMap<>();
        rules.put("rewardPoints", 20);
        rules.put("competencyId", competencyId);
        rules.put("questions", List.of(Map.of("type", "SINGLE", "content", "Ai được mở tài khoản?",
            "difficulty", "EASY", "section", "Phần 1", "options", List.of(
                Map.of("content", "Cá nhân đủ 15 tuổi", "correct", true),
                Map.of("content", "Ai cũng được", "correct", false)))));
        return rules;
    }

    private JsonNode webSession(long id) {
        return api.ok(INSTRUCTOR, get(SESSIONS + "/{id}", id));
    }

    private MockHttpServletRequestBuilder confirm(long id, int step, Object body) {
        return api.post(body, SESSIONS + "/{id}/steps/{step}/confirm", id, step);
    }

    /** Whether the worker's claim takes a step now; only external sessions exist in these tests. */
    private boolean workerClaims() {
        return transaction.execute(status -> drafting.claimNext()).isPresent();
    }

    private int turns(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_turns WHERE drafting_session_id = ?",
            Integer.class, id);
    }

    private long rowVersion(long revisionId) {
        return jdbc.queryForObject("SELECT row_version FROM learning_hub.course_revisions WHERE revision_id = ?",
            Long.class, revisionId);
    }

    private Map<String, Object> draft(long revisionId, String title, List<Object> chapters) {
        Map<String, Object> draft = new HashMap<>();
        draft.put("expectedVersion", rowVersion(revisionId));
        draft.put("title", title);
        draft.put("summary", "Quy trình.");
        draft.put("level", "basic");
        draft.put("defaultRequirement", "mandatory");
        draft.put("objectives", List.of("Kiểm tra được hồ sơ"));
        draft.put("chapters", chapters);
        draft.put("rewardPoints", 0);
        return draft;
    }

    private static Map<String, Object> lesson(String title) {
        Map<String, Object> lesson = new HashMap<>();
        lesson.put("title", title);
        lesson.put("lessonType", "content");
        lesson.put("estimatedDurationSeconds", 0);
        lesson.put("isRequired", true);
        Map<String, Object> block = new HashMap<>();
        block.put("blockType", "text");
        block.put("title", "Bài viết");
        block.put("body", "Nội dung tác giả viết.");
        block.put("bodyFormat", "markdown");
        block.put("completionMode", "manual");
        block.put("isRequired", true);
        block.put("assets", List.of());
        block.put("steps", List.of());
        lesson.put("blocks", List.of(block));
        return lesson;
    }

    @Test
    void toolsListNowHasProposeStep() {
        List<String> names = new java.util.ArrayList<>();
        mcp.result(token, "tools/list", Map.of()).path("tools").forEach(tool -> names.add(tool.path("name").asText()));
        assertThat(names).contains(PROPOSE);
    }

    @Test
    void aValidProposalForTheWaitingStepIsStoredAndShowsOnThePage() {
        long id = start();
        assertThat(workerClaims()).as("the worker never claims an external step").isFalse();

        JsonNode answered = propose(id, 1, basics()).data();
        assertThat(answered).isNotNull();
        assertThat(answered.path("waitingStep").isNull()).isTrue();
        assertThat(answered.path("steps").get(0).path("state").asText()).isEqualTo("proposed");
        assertThat(answered.path("next").asText()).contains("Chốt bước");

        JsonNode page = webSession(id);
        assertThat(page.path("proposer").asText()).isEqualTo("external");
        JsonNode step = page.path("steps").get(0);
        assertThat(step.path("state").asText()).isEqualTo("proposed");
        assertThat(step.path("proposal").path("title").asText()).isEqualTo("Mở tài khoản thanh toán");
        assertThat(step.path("proposal").path("code").asText()).startsWith("AI-");
        assertThat(step.path("proposal").path("programId").isNull()).isTrue();
        assertThat(workerClaims()).isFalse();
        assertThat(turns(id)).isZero();
    }

    @Test
    void aStepNotWaitingRefusesTheCallNamingItsState() {
        long id = start();
        assertThat(propose(id, 2, outline()).text()).contains("Step 2").contains("not the current step");

        propose(id, 1, basics());
        assertThat(propose(id, 1, basics()).text()).contains("Step 1").contains("proposed");

        jdbc.update("UPDATE learning_hub.course_drafting_steps SET state = 'failed' WHERE session_id = ?", id);
        assertThat(propose(id, 1, basics()).text()).contains("failed");
        jdbc.update("UPDATE learning_hub.course_drafting_steps SET state = 'manual' WHERE session_id = ?", id);
        assertThat(propose(id, 1, basics()).text()).contains("manual");

        long other = start();
        mcp.ok(token, ExternalAssistantTools.STOP, Map.of("session_id", other));
        McpClient.ToolResult stopped = propose(other, 1, basics());
        assertThat(stopped.error()).isTrue();
        assertThat(stopped.text()).contains("session is manual");

        // An in-house session is not reachable here at all.
        jdbc.update("UPDATE learning_hub.course_drafting_sessions SET proposer = 'assistant' WHERE session_id = ?",
            other);
        assertThat(propose(other, 1, basics()).text()).contains("not found");
    }

    @Test
    void anInvalidProposalOrAnUnknownCategoryOrUnitIsRefusedWithTheFieldsName() {
        long id = start();
        Map<String, Object> badLevel = basics();
        badLevel.put("level", "expert");
        assertThat(propose(id, 1, badLevel).text()).contains("level");

        Map<String, Object> unknownProgramme = basics();
        unknownProgramme.put("programId", 999_999_999L);
        McpClient.ToolResult refused = propose(id, 1, unknownProgramme);
        assertThat(refused.error()).isTrue();
        assertThat(refused.text()).contains("programId").contains("get_reference_data");

        Map<String, Object> unknownModule = basics();
        unknownModule.put("moduleId", 999_999_999L);
        assertThat(propose(id, 1, unknownModule).text()).contains("moduleId");

        assertThat(propose(id, 1, "not an object").text()).contains("proposal");
        assertThat(webSession(id).path("steps").get(0).path("state").asText()).isEqualTo("proposing");
    }

    @Test
    void theAuthorsConfirmedValuesAreWhatTheNextStepReadsAndProposeAgainWaitsForTheTroLyNgoai() {
        long id = start();
        propose(id, 1, basics());
        String code = webSession(id).path("steps").get(0).path("proposal").path("code").asText();

        // The author edits the title on the page before Chốt bước.
        Map<String, Object> draft = new HashMap<>();
        draft.put("title", "Tên tác giả sửa");
        draft.put("summary", "Quy trình.");
        draft.put("level", "basic");
        draft.put("defaultRequirement", "mandatory");
        draft.put("objectives", List.of("Kiểm tra được hồ sơ"));
        draft.put("chapters", List.of());
        draft.put("rewardPoints", 0);
        JsonNode created = api.ok(INSTRUCTOR, confirm(id, 1, Map.of("course", Map.of("ownerOrgId", ownOrg,
            "code", code, "title", "Tên tác giả sửa", "draft", draft))));
        long revisionId = created.path("revisionId").asLong();

        JsonNode read = mcp.ok(token, ExternalAssistantTools.GET_SESSION, Map.of("session_id", id)).data();
        assertThat(read.path("waitingStep").asInt()).isEqualTo(2);
        JsonNode confirmed = read.path("steps").get(0).path("confirmed");
        assertThat(confirmed.path("title").asText()).isEqualTo("Tên tác giả sửa");
        assertThat(confirmed.path("code").asText()).isEqualTo(code);
        assertThat(read.path("steps").get(1).path("confirmed").isNull()).isTrue();
        assertThat(workerClaims()).isFalse();

        // Step 2, asked again from the page: back to waiting for the Trợ lý ngoài, the worker still not involved.
        assertThat(propose(id, 2, outline()).error()).isFalse();
        assertThat(webSession(id).path("steps").get(1).path("proposal").path("chapters").get(0).path("lessons").get(0)
            .path("sourceSection").asText()).isEqualTo("Phần 1");
        JsonNode again = api.ok(INSTRUCTOR, post(SESSIONS + "/{id}/steps/{step}/propose-again", id, 2));
        assertThat(again.path("steps").get(1).path("state").asText()).isEqualTo("proposing");
        assertThat(workerClaims()).isFalse();
        assertThat(mcp.ok(token, ExternalAssistantTools.GET_SESSION, Map.of("session_id", id)).data()
            .path("waitingStep").asInt()).isEqualTo(2);
        assertThat(propose(id, 2, outline()).error()).isFalse();

        api.ok(INSTRUCTOR, confirm(id, 2, Map.of("draft", draft(revisionId, "Tên tác giả sửa",
            List.of(Map.of("title", "Chương tác giả", "lessons", List.of(lesson("Bài tác giả"))))))));
        JsonNode outline = mcp.ok(token, ExternalAssistantTools.GET_SESSION, Map.of("session_id", id)).data()
            .path("steps").get(1).path("confirmed");
        assertThat(outline.path("chapters").get(0).path("title").asText()).isEqualTo("Chương tác giả");
        assertThat(outline.path("chapters").get(0).path("lessons").get(0).path("title").asText())
            .isEqualTo("Bài tác giả");
        assertThat(outline.toString()).as("lesson content never reaches the Trợ lý ngoài")
            .doesNotContain("Nội dung tác giả viết");

        // Step 3: a competency outside the list is refused by name.
        assertThat(propose(id, 3, rules(999_999_999L)).text()).contains("competencyId");
        assertThat(propose(id, 3, rules(competency)).error()).isFalse();
        Map<String, Object> rulesDraft = draft(revisionId, "Tên tác giả sửa", List.of());
        rulesDraft.put("rewardPoints", 15);
        Map<String, Object> rulesBody = new HashMap<>();
        rulesBody.put("draft", rulesDraft);
        rulesBody.put("competencyId", null);
        rulesBody.put("questions", List.of());
        api.ok(INSTRUCTOR, confirm(id, 3, rulesBody));
        assertThat(mcp.ok(token, ExternalAssistantTools.GET_SESSION, Map.of("session_id", id)).data()
            .path("steps").get(2).path("confirmed").path("rewardPoints").asInt()).isEqualTo(15);

        // Step 4: a unit outside the list is refused by name; none is a valid proposal.
        assertThat(propose(id, 4, Map.of("targets", List.of(Map.of("key", "o999999999")))).text())
            .contains("targets[0].key");
        JsonNode audience = propose(id, 4, Map.of("targets", List.of())).data();
        assertThat(audience.path("steps").get(3).path("proposal").path("publishBlockers").isArray()).isTrue();
        api.ok(INSTRUCTOR, confirm(id, 4, Map.of()));
        assertThat(propose(id, 4, Map.of("targets", List.of())).text()).contains("session is finished");

        assertThat(turns(id)).isZero();
    }
}
