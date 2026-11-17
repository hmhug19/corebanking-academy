package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.SourceSummariser;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingWorker;
import vn.com.coreacademy.assistant.drafting.DraftingStepProposer;
import vn.com.coreacademy.assistant.drafting.DraftingStepProposer.Proposal;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.REPORT_VIEWER;

/** Starting a drafting session, its step 1 proposal and its failure; the model work is a stub here. */
class CourseDraftingIT extends AssessmentIntegrationTest {
    private static final String SESSIONS = "/api/v1/assistant/drafting-sessions";
    private static final String JOBS = "/api/v1/assistant/course-jobs";

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
    private long programme;
    private long competency;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        fixtures.course();
        competency = fixtures.competency();
        ownOrg = fixtures.orgId();
        programme = jdbc.queryForObject("SELECT min(program_id) FROM learning_hub.training_programs", Long.class);
    }

    @BeforeEach
    void freshQueue() {
        when(ollama.isAvailable()).thenReturn(true);
        when(proposer.propose(any(), any())).thenAnswer(invocation -> {
            DraftingStepRow step = invocation.getArgument(1);
            // Stubbing again calls the earlier answer once, with no arguments.
            if (step == null) {
                return null;
            }
            return new Proposal(json.readTree("""
                {"title":"Mở tài khoản","summary":"Quy trình.","objectives":["Thực hiện được"],"level":"basic",
                 "defaultRequirement":"mandatory","programId":null,"moduleId":null,"code":"AI-TESTCODE",
                 "unsure":["programId","moduleId"]}
                """), List.of("Chưa chọn được chương trình đào tạo phù hợp; hãy chọn trong danh sách."),
                json.readTree("{\"sections\":[{\"key\":\"s1\",\"title\":\"Mở\",\"summary\":\"Hồ sơ.\"}]}"));
        });
        clear();
    }

    @AfterAll
    void clear() {
        // Sessions and jobs share the daily allowance: none is left behind for other classes.
        jdbc.update("DELETE FROM learning_hub.course_drafting_sessions");
        jdbc.update("DELETE FROM learning_hub.course_generation_jobs");
    }

    private MockMultipartHttpServletRequestBuilder start(Map<String, Object> fields) {
        try {
            MockMultipartHttpServletRequestBuilder request = multipart(SESSIONS)
                .file(new MockMultipartFile("file", "quy-trinh.md", "text/markdown",
                    "# Quy trình\n\nNội dung.\n".getBytes(StandardCharsets.UTF_8)));
            if (fields != null) {
                request.file(new MockMultipartFile("fields", "", "application/json", json.writeValueAsBytes(fields)));
            }
            return request;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private MockHttpServletRequestBuilder session(long id) {
        return get(SESSIONS + "/{id}", id);
    }

    @Test
    void learnersAndReportViewersAreRefusedAndNothingIsStored() {
        int documentsBefore = documents();

        api.http(LEARNER, start(null), 403, "CORE-ACCESS_DENIED");
        api.http(REPORT_VIEWER, start(null), 403, "CORE-ACCESS_DENIED");

        assertThat(sessions()).isZero();
        assertThat(documents()).isEqualTo(documentsBefore);
    }

    @Test
    void anInstructorWithoutTheAssistantFunctionIsRefused() {
        long created = api.ok(INSTRUCTOR, start(null)).path("id").asLong();
        // A temporary password empties the user's rights, AI_ASSISTANT included.
        jdbc.update("UPDATE learning_hub.users SET must_change_password = true WHERE user_id = ?", INSTRUCTOR);
        try {
            api.http(INSTRUCTOR, start(null), 403, "CORE-ACCESS_DENIED");
            api.http(INSTRUCTOR, session(created), 403, "CORE-ACCESS_DENIED");
        } finally {
            jdbc.update("UPDATE learning_hub.users SET must_change_password = false WHERE user_id = ?", INSTRUCTOR);
        }
        assertThat(sessions()).isEqualTo(1);
    }

    @Test
    void startQueuesStepOneForTheInstructorsOwnUnitWithAPrivateSourceDocument() {
        JsonNode started = api.ok(INSTRUCTOR, start(Map.of("title", "Mở tài khoản", "level", "basic")));
        long id = started.path("id").asLong();
        long documentId = started.path("source").path("documentId").asLong();

        assertThat(started.path("status").asText()).isEqualTo("active");
        assertThat(started.path("currentStep").asInt()).isEqualTo(1);
        assertThat(started.path("courseId").isNull()).isTrue();
        assertThat(started.path("source").path("fileName").asText()).isEqualTo("quy-trinh.md");
        assertThat(started.path("steps")).hasSize(1);
        assertThat(started.path("steps").get(0).path("step").asInt()).isEqualTo(1);
        assertThat(started.path("steps").get(0).path("state").asText()).isEqualTo("proposing");
        assertThat(jdbc.queryForMap("SELECT owner_org_id, hints::text AS hints "
            + "FROM learning_hub.course_drafting_sessions WHERE session_id = ?", id)).containsEntry("owner_org_id", ownOrg)
            .hasEntrySatisfying("hints", hints -> assertThat(hints.toString()).contains("Mở tài khoản"));

        assertThat(jdbc.queryForMap("SELECT scope, uploaded_by FROM learning_hub.knowledge_documents "
            + "WHERE document_id = ?", documentId)).containsEntry("scope", "course_source")
            .containsEntry("uploaded_by", INSTRUCTOR);
        api.error(ADMIN, get("/api/v1/knowledge/documents/{id}", documentId), "CORE-RESOURCE_NOT_FOUND");
        api.error(ADMIN, session(id), "CORE-RESOURCE_NOT_FOUND");
        api.error(INSTRUCTOR, session(id + 1000), "CORE-RESOURCE_NOT_FOUND");
    }

    @Test
    void theWorkerProposesStepOneAndTheViewCarriesTheProposalAndItsNotes() {
        long id = api.ok(INSTRUCTOR, start(null)).path("id").asLong();

        assertThat(worker.runOnce()).isTrue();
        assertThat(worker.runOnce()).isFalse();

        JsonNode view = api.ok(INSTRUCTOR, session(id));
        JsonNode step = view.path("steps").get(0);
        assertThat(view.path("status").asText()).isEqualTo("active");
        assertThat(step.path("state").asText()).isEqualTo("proposed");
        assertThat(step.path("proposal").path("code").asText()).isEqualTo("AI-TESTCODE");
        assertThat(step.path("proposal").path("programId").isNull()).isTrue();
        assertThat(step.path("notes").get(0).asText()).startsWith("Chưa chọn được chương trình");
        assertThat(jdbc.queryForObject("SELECT source_summary->'sections'->0->>'key' "
            + "FROM learning_hub.course_drafting_sessions WHERE session_id = ?", String.class, id)).isEqualTo("s1");
    }

    @Test
    void anUnreadableOrThinDocumentEndsTheSessionAsFailedWithAMessage() {
        when(proposer.propose(any(), any())).thenThrow(new GenerationException(SourceSummariser.TOO_THIN));
        long id = api.ok(INSTRUCTOR, start(null)).path("id").asLong();

        worker.runOnce();

        JsonNode view = api.ok(INSTRUCTOR, session(id));
        assertThat(view.path("status").asText()).isEqualTo("failed");
        assertThat(view.path("error").asText()).isEqualTo(SourceSummariser.TOO_THIN);
        assertThat(view.path("steps").get(0).path("state").asText()).isEqualTo("failed");
        assertThat(view.path("steps").get(0).path("error").asText()).isEqualTo(SourceSummariser.TOO_THIN);
        assertThat(worker.runOnce()).isFalse();
    }

    @Test
    void sessionsAndOldJobsShareTheDailyAllowance() {
        Map<String, Object> inputs = Map.of("targetLearners", "Giao dịch viên", "level", "BEGINNER", "chapters", 3,
            "programmeId", programme, "competencyId", competency);
        api.ok(INSTRUCTOR, oldJob(inputs));
        api.ok(INSTRUCTOR, start(null));
        api.ok(INSTRUCTOR, start(null));
        int documentsBefore = documents();

        JsonNode refused = api.error(INSTRUCTOR, start(null), "ASSISTANT-JOB_RATE_LIMITED");

        assertThat(refused.path("errors").get(0).path("message").asText()).contains("thử lại sau");
        assertThat(sessions()).isEqualTo(2);
        assertThat(documents()).isEqualTo(documentsBefore);
        api.error(INSTRUCTOR, oldJob(inputs), "ASSISTANT-JOB_RATE_LIMITED");
        // other people have their own allowance
        api.ok(ADMIN, start(Map.of("ownerOrgId", ownOrg)));
    }

    @Test
    void anAdminDraftsForAUnitInTheirScopeAndAnInstructorCannotChooseAnother() {
        assertThat(api.ok(ADMIN, start(Map.of("ownerOrgId", ownOrg))).path("id").asLong()).isPositive();
        api.http(INSTRUCTOR, start(Map.of("ownerOrgId", ownOrg + 100_000)), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void invalidFieldsOrAnotherUsersConversationAreRefusedBeforeAnythingIsStored() {
        int documentsBefore = documents();
        api.error(INSTRUCTOR, start(Map.of("level", "BEGINNER")), "CORE-VALIDATION_FAILED");
        api.error(INSTRUCTOR, start(Map.of("title", "x".repeat(256))), "CORE-VALIDATION_FAILED");
        long foreign = jdbc.queryForObject("""
            INSERT INTO learning_hub.assistant_conversations (owner_id, title) VALUES (?, 'Của người khác')
            RETURNING conversation_id
            """, Long.class, LEARNER);
        api.error(INSTRUCTOR, start(null).param("conversationId", String.valueOf(foreign)), "CORE-VALIDATION_FAILED");

        assertThat(sessions()).isZero();
        assertThat(documents()).isEqualTo(documentsBefore);
        jdbc.update("DELETE FROM learning_hub.assistant_conversations WHERE conversation_id = ?", foreign);
    }

    // ---- confirming step 1 (ticket 04) -------------------------------------------------------------------------------

    @Test
    void confirmingStepOneCreatesTheDraftOnceLinksItAndQueuesStepTwo() {
        long id = proposedSession();
        Map<String, Object> body = basics("AI-CONFIRM01");

        JsonNode confirmed = api.ok(INSTRUCTOR, confirm(id, 1, body));

        long courseId = confirmed.path("courseId").asLong();
        assertThat(courseId).isPositive();
        assertThat(confirmed.path("revisionId").asLong()).isPositive();
        assertThat(confirmed.path("currentStep").asInt()).isEqualTo(2);
        assertThat(confirmed.path("steps")).hasSize(2);
        assertThat(confirmed.path("steps").get(0).path("state").asText()).isEqualTo("confirmed");
        assertThat(confirmed.path("steps").get(1).path("step").asInt()).isEqualTo(2);
        assertThat(confirmed.path("steps").get(1).path("state").asText()).isEqualTo("proposing");
        assertThat(jdbc.queryForMap("""
            SELECT c.owner_org_id, c.owner_user_id, r.title, r.summary, r.ai_generated
              FROM learning_hub.courses c JOIN learning_hub.course_revisions r ON r.course_id = c.course_id
             WHERE c.course_id = ?
            """, courseId)).containsEntry("owner_org_id", ownOrg).containsEntry("owner_user_id", INSTRUCTOR)
            .containsEntry("title", "Mở tài khoản").containsEntry("summary", "Quy trình.")
            .containsEntry("ai_generated", false);

        // a second press (or the chat card after the page) creates nothing new
        JsonNode again = api.ok(INSTRUCTOR, confirm(id, 1, body));
        assertThat(again.path("courseId").asLong()).isEqualTo(courseId);
        assertThat(again.path("currentStep").asInt()).isEqualTo(2);
        assertThat(courses("AI-CONFIRM01")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_drafting_steps WHERE session_id = ?",
            Integer.class, id)).isEqualTo(2);
    }

    @Test
    void theDraftsEditPageFindsItsSessionAgain() {
        long id = proposedSession();
        long courseId = api.ok(INSTRUCTOR, confirm(id, 1, basics("AI-RESUME01"))).path("courseId").asLong();

        JsonNode resumed = api.ok(INSTRUCTOR, get(SESSIONS + "/course/{courseId}", courseId));

        assertThat(resumed.path("id").asLong()).isEqualTo(id);
        assertThat(resumed.path("currentStep").asInt()).isEqualTo(2);
        assertThat(resumed.path("steps").get(1).path("state").asText()).isEqualTo("proposing");
        // another author, or a course without a session, has none
        assertThat(api.ok(ADMIN, get(SESSIONS + "/course/{courseId}", courseId)).isNull()).isTrue();
        assertThat(api.ok(INSTRUCTOR, get(SESSIONS + "/course/{courseId}", courseId + 100_000)).isNull()).isTrue();
        api.http(LEARNER, get(SESSIONS + "/course/{courseId}", courseId), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void aTakenCodeReturnsTheFieldErrorAndTheSessionProposesANewCode() {
        long id = proposedSession();
        String taken = jdbc.queryForObject("SELECT min(code) FROM learning_hub.courses", String.class);
        int before = courses(taken);

        JsonNode refused = api.error(INSTRUCTOR, confirm(id, 1, basics(taken)), "LEARNING-DUPLICATE");

        assertThat(refused.path("errors").get(0).path("field").asText()).isEqualTo("course.code");
        assertThat(courses(taken)).isEqualTo(before);
        JsonNode view = api.ok(INSTRUCTOR, session(id));
        JsonNode step = view.path("steps").get(0);
        String proposed = step.path("proposal").path("code").asText();
        assertThat(view.path("courseId").isNull()).isTrue();
        assertThat(view.path("currentStep").asInt()).isEqualTo(1);
        assertThat(view.path("steps")).hasSize(1);
        assertThat(step.path("state").asText()).isEqualTo("proposed");
        assertThat(proposed).startsWith("AI-").isNotEqualTo("AI-TESTCODE").isNotEqualToIgnoringCase(taken);
        assertThat(step.path("proposal").path("title").asText()).isEqualTo("Mở tài khoản");
        assertThat(step.path("notes").toString()).contains(taken).contains(proposed);

        // the new code goes through
        assertThat(api.ok(INSTRUCTOR, confirm(id, 1, basics(proposed))).path("courseId").asLong()).isPositive();
    }

    @Test
    void aStepIsConfirmedOnlyWhenItIsTheCurrentOneAndHasBeenProposed() {
        long id = api.ok(INSTRUCTOR, start(null)).path("id").asLong();

        JsonNode early = api.error(INSTRUCTOR, confirm(id, 1, basics("AI-EARLY001")), "CORE-VALIDATION_FAILED");
        assertThat(early.path("errors").get(0).path("field").asText()).isEqualTo("step");
        assertThat(early.path("errors").get(0).path("code").asText()).isEqualTo("INVALID_STATE");

        worker.runOnce();
        api.error(INSTRUCTOR, confirm(id, 2, Map.of()), "CORE-VALIDATION_FAILED");
        api.error(INSTRUCTOR, confirm(id, 5, basics("AI-EARLY002")), "CORE-VALIDATION_FAILED");
        assertThat(courses("AI-EARLY001") + courses("AI-EARLY002")).isZero();
        assertThat(api.ok(INSTRUCTOR, session(id)).path("courseId").isNull()).isTrue();
    }

    @Test
    void anInvalidBodyAnotherAuthorOrALearnerCreateNothing() {
        long id = proposedSession();
        int before = jdbc.queryForObject("SELECT count(*) FROM learning_hub.courses", Integer.class);

        JsonNode invalid = api.error(INSTRUCTOR, confirm(id, 1, Map.of("course", Map.of("ownerOrgId", ownOrg))),
            "CORE-VALIDATION_FAILED");
        assertThat(invalid.path("errors").toString()).contains("course.code");
        api.error(INSTRUCTOR, confirm(id, 1, Map.of()), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, confirm(id, 1, basics("AI-FOREIGN1")), "CORE-RESOURCE_NOT_FOUND");
        api.http(LEARNER, confirm(id, 1, basics("AI-FOREIGN2")), 403, "CORE-ACCESS_DENIED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.courses", Integer.class)).isEqualTo(before);
    }

    private long proposedSession() {
        long id = api.ok(INSTRUCTOR, start(null)).path("id").asLong();
        assertThat(worker.runOnce()).isTrue();
        return id;
    }

    private MockHttpServletRequestBuilder confirm(long id, int step, Object body) {
        return api.post(body, SESSIONS + "/{id}/steps/{step}/confirm", id, step);
    }

    /** What the page sends for step 1: the create command with the form's draft. */
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

    private int courses(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.courses WHERE lower(code) = lower(?)",
            Integer.class, code);
    }

    private MockMultipartHttpServletRequestBuilder oldJob(Map<String, Object> inputs) {
        return multipart(JOBS)
            .file(new MockMultipartFile("file", "a.md", "text/markdown",
                "# A\n\nB.\n".getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("inputs", "", "application/json", write(inputs)));
    }

    private byte[] write(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private int sessions() {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_drafting_sessions", Integer.class);
    }

    private int documents() {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.knowledge_documents", Integer.class);
    }
}
