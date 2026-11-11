package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.tool.StartCourseJobTool;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.dto.UserRights;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;

/** Starting a course job from the chatbot: the tool only proposes, the confirmed card makes the same job as the form. */
class StartCourseJobToolIT extends AssessmentIntegrationTest {
    @MockitoBean
    private OllamaClient ollama;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StartCourseJobTool tool;

    private ApiClient api;
    private String programmeName;
    private String competencyName;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        fixtures.course();
        // a name no other fixture shares, so the tool's lookup by name finds exactly one
        competencyName = "Năng lực duy nhất " + System.nanoTime();
        jdbc.update("INSERT INTO learning_hub.competencies(code, name, created_by, updated_by) VALUES (?, ?, '000001', '000001')",
            "UQ" + (System.nanoTime() % 1_000_000_000L), competencyName);
        programmeName = jdbc.queryForObject("SELECT name FROM learning_hub.training_programs ORDER BY program_id LIMIT 1",
            String.class);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM learning_hub.course_generation_jobs");
    }

    private long upload(String user) throws Exception {
        var response = api.raw(user, multipart("/api/v1/knowledge/documents")
            .file(new MockMultipartFile("file", "q.md", "text/markdown", "# Q\n\nNội dung.\n"
                .getBytes(StandardCharsets.UTF_8))).param("scope", "personal"));
        return json.readTree(response.getContentAsString(StandardCharsets.UTF_8)).path("data").path("id").asLong();
    }

    private static AgentContext context(String userId, String role, Long document) {
        UserRights rights = new UserRights(List.of(new RoleGrant(role, role, null)), List.of(), Map.of());
        return new AgentContext(userId, null, rights).withAttachment(document);
    }

    private JsonNode args(String programme, String competency) {
        var node = json.createObjectNode().put("targetLearners", "Giao dịch viên mới").put("level", "BEGINNER")
            .put("chapters", 3);
        if (programme != null) {
            node.put("programmeName", programme);
        }
        if (competency != null) {
            node.put("competencyName", competency);
        }
        return node;
    }

    @Test
    void missingInputsAreAskedForAndNothingIsStored() throws Exception {
        long document = upload(INSTRUCTOR);

        ToolOutcome outcome = tool.run(args(null, null), context(INSTRUCTOR, "INSTRUCTOR", document), new TurnState());

        assertThat(outcome).isInstanceOf(ToolOutcome.Result.class);
        assertThat(((ToolOutcome.Result) outcome).text()).contains("chương trình đào tạo").contains("năng lực");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_pending_actions", Integer.class))
            .isZero();
    }

    @Test
    void withoutAFileTheUserIsToldToAttachOneAndSomeoneElsesFileIsNotAccepted() throws Exception {
        long document = upload(INSTRUCTOR);

        assertThat(((ToolOutcome.Result) tool.run(args(programmeName, competencyName),
            context(INSTRUCTOR, "INSTRUCTOR", null), new TurnState())).text()).contains("đính kèm");
        assertThat(((ToolOutcome.Result) tool.run(args(programmeName, competencyName),
            context("000008", "INSTRUCTOR", document), new TurnState())).text()).contains("đính kèm");
    }

    @Test
    void learnersAreNotOfferedTheToolAndAdminsAreSentToTheForm() {
        assertThat(tool.availableTo(context(LEARNER, "LEARNER", null))).isFalse();
        assertThat(tool.availableTo(context(INSTRUCTOR, "INSTRUCTOR", null))).isTrue();
        assertThat(((ToolOutcome.Result) tool.run(args(programmeName, competencyName),
            context("000002", "ADMIN", 1L), new TurnState())).text()).contains("Tạo bằng AI");
    }

    @Test
    void confirmingTheCardStartsTheSameJobTheFormDoes() throws Exception {
        long document = upload(INSTRUCTOR);

        ToolOutcome outcome = tool.run(args(programmeName, competencyName), context(INSTRUCTOR, "INSTRUCTOR", document),
            new TurnState());
        assertThat(outcome).isInstanceOf(ToolOutcome.ActionRequired.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_generation_jobs", Integer.class))
            .isZero();

        String actionId = ((ToolOutcome.ActionRequired) outcome).action().id();
        JsonNode result = api.ok(INSTRUCTOR, api.post(Map.of(),
            "/api/v1/assistant/agent/actions/" + actionId + "/confirm"));

        long jobId = result.path("data").path("jobId").asLong();
        assertThat(result.path("data").path("path").asText()).isEqualTo("/admin/courses/ai-drafts/" + jobId);
        assertThat(jdbc.queryForMap("SELECT status, source_document_id FROM learning_hub.course_generation_jobs "
            + "WHERE job_id = ?", jobId)).containsEntry("status", "queued").containsEntry("source_document_id", document);
        // the file is used once
        assertThat(((ToolOutcome.Result) tool.run(args(programmeName, competencyName),
            context(INSTRUCTOR, "INSTRUCTOR", document), new TurnState())).text()).contains("đính kèm");
    }
}
