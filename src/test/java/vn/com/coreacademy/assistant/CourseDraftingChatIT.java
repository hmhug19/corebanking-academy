package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.assistant.agent.AgentService;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.SourceSummariser;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingWorker;
import vn.com.coreacademy.assistant.drafting.DraftingStepProposer;
import vn.com.coreacademy.assistant.drafting.DraftingStepProposer.Proposal;
import vn.com.coreacademy.knowledge.ai.ChatTurn;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.ToolCall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.REPORT_VIEWER;

/**
 * Soạn cùng trợ lý from the chat: the attached file is uploaded as a private source, start_course_drafting starts the
 * session for this conversation and opens the new course page on it, and each ready step comes back to the
 * conversation as one message whose "Đã chốt" card shows the step's real state. The model work is a stub here.
 */
class CourseDraftingChatIT extends AssessmentIntegrationTest {
    private static final String SESSIONS = "/api/v1/assistant/drafting-sessions";
    private static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    private static final String ASK = "Tạo khóa học từ tài liệu này";

    @MockitoBean
    private OllamaClient ollama;
    @MockitoBean
    private DraftingStepProposer proposer;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CourseDraftingWorker worker;

    private ApiClient api;
    private long ownOrg;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        ownOrg = new AssessmentFixtures(api, jdbc).orgId();
    }

    @BeforeEach
    void fresh() {
        doReturn(true).when(ollama).isAvailable();
        when(proposer.propose(any(), any())).thenAnswer(this::proposal);
        clear();
    }

    @AfterAll
    void clear() {
        // Sessions and jobs share the daily allowance: none is left behind for other classes.
        // Steps have no foreign key to their session (guideline §13.2): they go with it here.
        jdbc.update("DELETE FROM learning_hub.course_drafting_steps");
        jdbc.update("DELETE FROM learning_hub.course_drafting_sessions");
        jdbc.update("DELETE FROM learning_hub.course_drafting_reproposals");
        jdbc.update("DELETE FROM learning_hub.course_generation_jobs");
        // Course sources no session uses count against the upload cap: none is left behind either.
        jdbc.update("DELETE FROM learning_hub.knowledge_document_chunks WHERE document_id IN "
            + "(SELECT document_id FROM learning_hub.knowledge_documents WHERE scope = 'course_source')");
        jdbc.update("DELETE FROM learning_hub.knowledge_documents WHERE scope = 'course_source'");
    }

    /** What the stub proposes. Stubbing again calls the earlier answer once, with no arguments. */
    private Proposal proposal(InvocationOnMock invocation) throws Exception {
        DraftingStepRow step = invocation.getArgument(1);
        if (step != null && step.stepNo() == 2) {
            return new Proposal(json.readTree("""
                {"chapters":[{"title":"Chuẩn bị","lessons":[{"title":"Hồ sơ","description":"Giấy tờ.",
                  "sourceSection":"Hồ sơ"}]}]}
                """), List.of("Đã đề xuất 1 chương, 1 bài; nội dung từng bài để trống."), null);
        }
        return new Proposal(json.readTree("""
            {"title":"Mở tài khoản","summary":"Quy trình.","objectives":["Thực hiện được"],"level":"basic",
             "defaultRequirement":"mandatory","programId":null,"moduleId":null,"code":"AI-CHAT0001",
             "unsure":["programId","moduleId"]}
            """), List.of("Chưa chọn được chương trình đào tạo phù hợp; hãy chọn trong danh sách."),
            json.readTree("{\"sections\":[{\"key\":\"s1\",\"title\":\"Hồ sơ\",\"summary\":\"Giấy tờ.\"}]}"));
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "quy-trinh.md", "text/markdown",
            "# Quy trình\n\nNội dung.\n".getBytes(StandardCharsets.UTF_8));
    }

    /** The chat's attachment, uploaded before the turn. */
    private long upload(String user) {
        return api.ok(user, multipart(SESSIONS + "/sources").file(file())).path("documentId").asLong();
    }

    /** One chat turn of the course skill in which the model calls the tool with {@code arguments}. */
    private List<JsonNode> turn(String user, Long documentId, ObjectNode arguments) throws Exception {
        int[] calls = {0};
        doAnswer(invocation -> calls[0]++ == 0
            ? new ChatTurn("", List.of(ToolCall.of("start_course_drafting", arguments)))
            : new ChatTurn("Mình đã tạo xong khóa học.", List.of()))
            .when(ollama).chatWithTools(anyList(), anyList(), any(), any(), any());
        return stream(user, documentId);
    }

    private List<JsonNode> stream(String user, Long documentId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("message", ASK);
        body.put("skill", "tao_khoa_hoc");
        if (documentId != null) {
            body.put("attachmentDocumentId", documentId);
        }
        MvcResult started = mvc.perform(post("/api/v1/assistant/agent/stream")
                .with(jwt().jwt(token -> token.subject(user))).accept(NDJSON, MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(request().asyncStarted()).andReturn();
        MvcResult result = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();
        List<JsonNode> events = new ArrayList<>();
        for (String line : result.getResponse().getContentAsString(StandardCharsets.UTF_8).lines().toList()) {
            events.add(json.readTree(line));
        }
        return events;
    }

    private static JsonNode last(List<JsonNode> events) {
        return events.get(events.size() - 1);
    }

    private static long conversationOf(List<JsonNode> events) {
        assertThat(events.get(0).path("type").asText()).isEqualTo("conversation");
        return events.get(0).path("conversationId").asLong();
    }

    private JsonNode turns(String user, long conversationId) {
        return api.ok(user, get("/api/v1/assistant/conversations/{id}", conversationId)).path("turns");
    }

    private int sessions() {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_drafting_sessions", Integer.class);
    }

    private Map<String, Object> basics(String code) {
        Map<String, Object> draft = new HashMap<>();
        draft.put("title", "Mở tài khoản");
        draft.put("summary", "Quy trình.");
        draft.put("level", "basic");
        draft.put("defaultRequirement", "mandatory");
        draft.put("objectives", List.of("Thực hiện được"));
        draft.put("chapters", List.of());
        draft.put("rewardPoints", 0);
        return Map.of("course", Map.of("ownerOrgId", ownOrg, "code", code, "title", "Mở tài khoản", "draft", draft));
    }

    @Test
    void theChatFileBecomesAPrivateSourceThatOnlyRolesManagingCoursesMayUpload() {
        long documentId = upload(INSTRUCTOR);

        assertThat(jdbc.queryForMap("SELECT scope, uploaded_by FROM learning_hub.knowledge_documents "
            + "WHERE document_id = ?", documentId)).containsEntry("scope", "course_source")
            .containsEntry("uploaded_by", INSTRUCTOR);
        api.error(ADMIN, get("/api/v1/knowledge/documents/{id}", documentId), "CORE-RESOURCE_NOT_FOUND");
        assertThat(sessions()).isZero();
        for (String user : List.of(LEARNER, REPORT_VIEWER)) {
            api.http(user, multipart(SESSIONS + "/sources").file(file()), 403, "CORE-ACCESS_DENIED");
        }
    }

    @Test
    void withADocumentTheToolStartsASessionForThisConversationAndOpensTheNewCoursePageOnIt() throws Exception {
        long documentId = upload(INSTRUCTOR);

        List<JsonNode> events = turn(INSTRUCTOR, documentId, json.createObjectNode().put("title", "Mở tài khoản"));

        long conversationId = conversationOf(events);
        Map<String, Object> session = jdbc.queryForMap("SELECT session_id, conversation_id, source_document_id, "
            + "hints::text AS hints FROM learning_hub.course_drafting_sessions");
        assertThat(session).containsEntry("conversation_id", conversationId)
            .containsEntry("source_document_id", documentId);
        assertThat(session.get("hints").toString()).contains("Mở tài khoản");
        assertThat(events.subList(1, events.size())).extracting(event -> event.path("type").asText())
            .containsExactly("tool", "navigate", "done");
        assertThat(events.get(2).path("path").asText())
            .isEqualTo("/admin/courses/new?session=" + session.get("session_id"));
        assertThat(events.get(2).path("title").asText()).isEqualTo("Khóa học mới");
        // The answer is the tool's own: the model's claim never reaches the user.
        assertThat(last(events).path("answer").asText()).contains("Đã chốt").doesNotContain("tạo xong");

        // The same document cannot start a second session.
        List<JsonNode> again = turn(INSTRUCTOR, documentId, json.createObjectNode());
        assertThat(again).extracting(event -> event.path("type").asText()).doesNotContain("navigate");
        assertThat(last(again).path("answer").asText()).isEqualTo("Không tìm thấy tài liệu đính kèm.");
        assertThat(sessions()).isEqualTo(1);
    }

    @Test
    void withoutADocumentTheToolAsksForOneAndNothingStarts() throws Exception {
        List<JsonNode> events = turn(INSTRUCTOR, null, json.createObjectNode().put("title", "Khóa thử"));

        assertThat(events).extracting(event -> event.path("type").asText()).doesNotContain("navigate");
        assertThat(last(events).path("answer").asText()).contains("đính kèm tài liệu");
        assertThat(sessions()).isZero();
    }

    @Test
    void anotherUsersDocumentStartsNothing() throws Exception {
        long theirs = upload(ADMIN);

        List<JsonNode> events = turn(INSTRUCTOR, theirs, json.createObjectNode());

        assertThat(events).extracting(event -> event.path("type").asText()).doesNotContain("navigate");
        assertThat(last(events).path("answer").asText()).isEqualTo("Không tìm thấy tài liệu đính kèm.");
        assertThat(sessions()).isZero();
    }

    @Test
    void aModelThatOnlyWritesTextStartsNothingAndItsClaimIsNotShown() throws Exception {
        long documentId = upload(INSTRUCTOR);
        doReturn(new ChatTurn("Mình đã tạo khóa học cho bạn.", List.of())).when(ollama)
            .chatWithTools(anyList(), anyList(), any(), any(), any());

        List<JsonNode> events = stream(INSTRUCTOR, documentId);

        assertThat(last(events).path("answer").asText()).isEqualTo(AgentService.TOOL_MISSING_ANSWER);
        assertThat(sessions()).isZero();
    }

    @Test
    void eachReadyStepComesBackAsOneMessageWhoseCardShowsTheStepsRealOutcome() throws Exception {
        long documentId = upload(INSTRUCTOR);
        long conversationId = conversationOf(turn(INSTRUCTOR, documentId, json.createObjectNode()));
        long sessionId = jdbc.queryForObject("SELECT session_id FROM learning_hub.course_drafting_sessions",
            Long.class);
        assertThat(turns(INSTRUCTOR, conversationId)).hasSize(1);

        assertThat(worker.runOnce()).isTrue();

        JsonNode turns = turns(INSTRUCTOR, conversationId);
        assertThat(turns).hasSize(2);
        JsonNode message = turns.get(1);
        assertThat(message.path("question").asText()).isEmpty();
        assertThat(message.path("status").asText()).isEqualTo("done");
        assertThat(message.path("answer").asText()).startsWith("Bước 1 · Thông tin cơ bản:")
            .contains("Chưa chọn được chương trình đào tạo").contains("Đã chốt");
        assertThat(message.path("draftingCard").path("sessionId").asLong()).isEqualTo(sessionId);
        assertThat(message.path("draftingCard").path("step").asInt()).isEqualTo(1);
        assertThat(message.path("draftingCard").path("state").asText()).isEqualTo("proposed");
        assertThat(message.path("draftingCard").path("courseId").isNull()).isTrue();
        assertThat(turns.get(0).has("draftingCard")).isFalse();

        // Confirming (on the page or from the card) is what the card shows once the conversation is reopened.
        long courseId = api.ok(INSTRUCTOR, api.post(basics("AI-CHATCONF1"), SESSIONS + "/{id}/steps/1/confirm",
            sessionId)).path("courseId").asLong();
        assertThat(worker.runOnce()).isTrue();

        turns = turns(INSTRUCTOR, conversationId);
        assertThat(turns).hasSize(3);
        assertThat(turns.get(1).path("draftingCard").path("state").asText()).isEqualTo("confirmed");
        assertThat(turns.get(1).path("draftingCard").path("courseId").asLong()).isEqualTo(courseId);
        assertThat(turns.get(2).path("answer").asText()).startsWith("Bước 2 · Nội dung khóa học:")
            .contains("nội dung bài để trống");
        assertThat(turns.get(2).path("draftingCard").path("step").asInt()).isEqualTo(2);
        assertThat(turns.get(2).path("draftingCard").path("state").asText()).isEqualTo("proposed");
        // Nothing more is queued, so no other message arrives.
        assertThat(worker.runOnce()).isFalse();
        assertThat(turns(INSTRUCTOR, conversationId)).hasSize(3);
    }

    @Test
    void aDocumentThatCannotBeReadIsToldInTheChatWithTheReason() throws Exception {
        when(proposer.propose(any(), any())).thenThrow(new GenerationException(SourceSummariser.TOO_THIN));
        long conversationId = conversationOf(turn(INSTRUCTOR, upload(INSTRUCTOR), json.createObjectNode()));

        worker.runOnce();

        JsonNode message = turns(INSTRUCTOR, conversationId).get(1);
        assertThat(message.path("answer").asText()).contains(SourceSummariser.TOO_THIN).contains("Thử lại");
        assertThat(message.path("draftingCard").path("state").asText()).isEqualTo("failed");
    }

    @Test
    void aSessionStartedFromThePageAloneSendsNoMessage() {
        api.ok(INSTRUCTOR, multipart(SESSIONS).file(file()));
        int turnsBefore = jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_turns", Integer.class);

        assertThat(worker.runOnce()).isTrue();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_turns", Integer.class))
            .isEqualTo(turnsBefore);
    }
}
