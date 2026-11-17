package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
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
import vn.com.coreacademy.assistant.drafting.DraftingPublishCheck;
import vn.com.coreacademy.assistant.drafting.DraftingTargets;
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
    private static final String OUTLINE = """
        {"chapters":[{"title":"Chuẩn bị","lessons":[
          {"title":"Điều kiện mở tài khoản","description":"Ai được mở tài khoản.","sourceSection":"Điều kiện"},
          {"title":"Hồ sơ","description":"Giấy tờ khách hàng nộp.","sourceSection":"Hồ sơ và giấy tờ"}]}]}
        """;
    private static final String RULES = """
        {"rewardPoints":30,"competencyId":null,"questions":[
          {"type":"SINGLE","content":"Ai được mở tài khoản?","explanation":"Theo điều kiện.","difficulty":"EASY",
           "options":[{"content":"Cá nhân đủ 15 tuổi","correct":true},{"content":"Ai cũng được","correct":false}],
           "sourceSection":"Điều kiện"},
          {"type":"SINGLE","content":"Giấy tờ nào bắt buộc?","explanation":null,"difficulty":"MEDIUM",
           "options":[{"content":"Căn cước","correct":true},{"content":"Sổ đỏ","correct":false}],
           "sourceSection":"Hồ sơ và giấy tờ"},
          {"type":"MULTIPLE","content":"Hồ sơ gồm?","explanation":null,"difficulty":"HARD",
           "options":[{"content":"Giấy đề nghị","correct":true},{"content":"Căn cước","correct":true},
                      {"content":"Sổ hộ khẩu","correct":false}],
           "sourceSection":"Hồ sơ và giấy tờ"}]}
        """;

    private static final String AUDIENCE = """
        {"targets":[{"targetType":"org","orgId":%d,"includeDescendants":false,"label":"Đơn vị"}],
         "publishBlockers":["1 bài chưa có nội dung"]}
        """;

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
    @Autowired
    private DraftingPublishCheck publishCheck;
    @Autowired
    private DraftingTargets targets;

    private ApiClient api;
    private long ownOrg;
    private long programme;
    private long competency;
    private long ownProgramme;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        long fixtureRevision = fixtures.course().revisionId();
        ownProgramme = jdbc.queryForObject("SELECT program_id FROM learning_hub.course_revisions WHERE revision_id = ?",
            Long.class, fixtureRevision);
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
            if (step.stepNo() == 2) {
                return new Proposal(json.readTree(OUTLINE), List.of("Đã đề xuất 1 chương, 2 bài."), null);
            }
            if (step.stepNo() == 3) {
                return new Proposal(json.readTree(RULES), List.of("Đã đề xuất 3 câu hỏi nháp."), null);
            }
            if (step.stepNo() == 4) {
                return new Proposal(json.readTree(AUDIENCE.formatted(ownOrg)), List.of("Đề xuất giao cho: Đơn vị."),
                    null);
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

    // ---- step 2, the outline (ticket 05) -----------------------------------------------------------------------------

    @Test
    void confirmingStepTwoSavesEmptyLessonsMarksTheRevisionAndQueuesStepThree() {
        long id = proposedSession();
        JsonNode created = api.ok(INSTRUCTOR, confirm(id, 1, basics("AI-OUTLINE1")));
        long revisionId = created.path("revisionId").asLong();
        assertThat(worker.runOnce()).isTrue();
        JsonNode proposed = api.ok(INSTRUCTOR, session(id)).path("steps").get(1);
        assertThat(proposed.path("state").asText()).isEqualTo("proposed");
        assertThat(proposed.path("proposal").path("chapters").get(0).path("lessons").get(1).path("sourceSection")
            .asText()).isEqualTo("Hồ sơ và giấy tờ");

        // the page sends the outline as written, plus a lesson the author added: all of them without content
        JsonNode confirmed = api.ok(INSTRUCTOR, confirm(id, 2, outlineBody(revisionId,
            lesson("Điều kiện mở tài khoản", List.of()), lesson("Hồ sơ", List.of()),
            lesson("Bài tác giả thêm", List.of()))));

        assertThat(confirmed.path("currentStep").asInt()).isEqualTo(3);
        assertThat(confirmed.path("steps")).hasSize(3);
        JsonNode outline = confirmed.path("steps").get(1);
        assertThat(outline.path("state").asText()).isEqualTo("confirmed");
        assertThat(outline.path("proposal").has("lessonNotes")).isFalse();
        assertThat(confirmed.path("steps").get(2).path("step").asInt()).isEqualTo(3);
        assertThat(confirmed.path("steps").get(2).path("state").asText()).isEqualTo("proposing");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM learning_hub.course_lessons l
              JOIN learning_hub.course_chapters c ON c.chapter_id = l.chapter_id WHERE c.revision_id = ?
            """, Integer.class, revisionId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM learning_hub.lesson_blocks b
              JOIN learning_hub.course_lessons l ON l.lesson_id = b.lesson_id
              JOIN learning_hub.course_chapters c ON c.chapter_id = l.chapter_id WHERE c.revision_id = ?
            """, Integer.class, revisionId)).isZero();
        assertThat(jdbc.queryForObject("SELECT ai_generated FROM learning_hub.course_revisions WHERE revision_id = ?",
            Boolean.class, revisionId)).isTrue();

        // each proposed lesson keeps its description and source on the session, by saved lesson id
        JsonNode notes = outline.path("lessonNotes");
        assertThat(notes).hasSize(2);
        long savedId = jdbc.queryForObject("""
            SELECT l.lesson_id FROM learning_hub.course_lessons l
              JOIN learning_hub.course_chapters c ON c.chapter_id = l.chapter_id
             WHERE c.revision_id = ? AND l.title = 'Hồ sơ'
            """, Long.class, revisionId);
        assertThat(notes.get(1).path("lessonId").asLong()).isEqualTo(savedId);
        assertThat(notes.get(1).path("description").asText()).isEqualTo("Giấy tờ khách hàng nộp.");
        assertThat(notes.get(1).path("sourceSection").asText()).isEqualTo("Hồ sơ và giấy tờ");
        assertThat(api.ok(INSTRUCTOR, session(id)).path("steps").get(1).path("lessonNotes")).isEqualTo(notes);

        // step 2 cannot be confirmed twice
        api.error(INSTRUCTOR, confirm(id, 2, outlineBody(revisionId)), "CORE-VALIDATION_FAILED");
    }

    @Test
    void aProposedLessonSentWithContentIsRefusedAndNothingIsSaved() {
        long id = proposedSession();
        long revisionId = api.ok(INSTRUCTOR, confirm(id, 1, basics("AI-OUTLINE2"))).path("revisionId").asLong();
        worker.runOnce();
        Map<String, Object> block = new HashMap<>();
        block.put("blockType", "text");
        block.put("title", "Giới thiệu");
        block.put("body", "Nội dung.");
        block.put("bodyFormat", "markdown");
        block.put("completionMode", "manual");
        block.put("isRequired", true);
        block.put("assets", List.of());
        block.put("steps", List.of());

        JsonNode refused = api.error(INSTRUCTOR, confirm(id, 2, outlineBody(revisionId,
            lesson("Điều kiện mở tài khoản", List.of(block)), lesson("Hồ sơ", List.of()))),
            "CORE-VALIDATION_FAILED");

        assertThat(refused.path("errors").get(0).path("code").asText()).isEqualTo("CONTENT_NOT_EMPTY");
        JsonNode view = api.ok(INSTRUCTOR, session(id));
        assertThat(view.path("currentStep").asInt()).isEqualTo(2);
        assertThat(view.path("steps").get(1).path("state").asText()).isEqualTo("proposed");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM learning_hub.course_chapters WHERE revision_id = ?
            """, Integer.class, revisionId)).isZero();
        assertThat(jdbc.queryForObject("SELECT ai_generated FROM learning_hub.course_revisions WHERE revision_id = ?",
            Boolean.class, revisionId)).isFalse();
    }

    @Test
    void stepTwoIsConfirmedOnlyOnceProposedAndWithAValidDraft() {
        long id = proposedSession();
        long revisionId = api.ok(INSTRUCTOR, confirm(id, 1, basics("AI-OUTLINE3"))).path("revisionId").asLong();

        // still being proposed
        api.error(INSTRUCTOR, confirm(id, 2, outlineBody(revisionId)), "CORE-VALIDATION_FAILED");
        worker.runOnce();
        api.error(INSTRUCTOR, confirm(id, 2, Map.of()), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, confirm(id, 2, outlineBody(revisionId)), "CORE-RESOURCE_NOT_FOUND");
        api.error(INSTRUCTOR, confirm(id, 3, outlineBody(revisionId)), "CORE-VALIDATION_FAILED");

        assertThat(api.ok(INSTRUCTOR, session(id)).path("currentStep").asInt()).isEqualTo(2);
    }

    // ---- step 3, rules and draft questions (ticket 06) ---------------------------------------------------------------

    /** A session at step 3 with its rules proposed; returns {sessionId, courseId, revisionId}. */
    private long[] rulesProposed(String code) {
        long id = proposedSession();
        JsonNode created = api.ok(INSTRUCTOR, confirm(id, 1, basics(code)));
        long courseId = created.path("courseId").asLong();
        long revisionId = created.path("revisionId").asLong();
        worker.runOnce();
        api.ok(INSTRUCTOR, confirm(id, 2, outlineBody(revisionId, lesson("Điều kiện mở tài khoản", List.of()))));
        assertThat(worker.runOnce()).isTrue();
        return new long[] {id, courseId, revisionId};
    }

    @Test
    void stepThreeProposesQuestionsThatStayOutOfTheBankUntilConfirmed() {
        int bankBefore = bank();
        long[] ids = rulesProposed("AI-RULES001");

        JsonNode step = api.ok(INSTRUCTOR, session(ids[0])).path("steps").get(2);
        assertThat(step.path("state").asText()).isEqualTo("proposed");
        assertThat(step.path("proposal").path("rewardPoints").asInt()).isEqualTo(30);
        assertThat(step.path("proposal").path("questions")).hasSize(3);
        assertThat(step.path("proposal").path("questions").get(1).path("sourceSection").asText())
            .isEqualTo("Hồ sơ và giấy tờ");
        assertThat(bank()).isEqualTo(bankBefore);
        assertThat(finalExams(ids[1])).isZero();
    }

    @Test
    void confirmingStepThreeCreatesANewFinalExamFromTheKeptQuestionsOnly() {
        long[] ids = rulesProposed("AI-RULES002");
        JsonNode proposed = api.ok(INSTRUCTOR, session(ids[0])).path("steps").get(2).path("proposal");
        int bankBefore = bank();
        // the author removed the second question and edited the third
        ObjectNode edited = proposed.path("questions").get(2).deepCopy();
        edited.put("content", "Hồ sơ mở tài khoản gồm những gì?");
        List<JsonNode> kept = List.of(proposed.path("questions").get(0), edited);

        JsonNode confirmed = api.ok(INSTRUCTOR, confirm(ids[0], 3, rulesBody(ids[2], 45, competency, kept)));

        assertThat(confirmed.path("currentStep").asInt()).isEqualTo(4);
        assertThat(confirmed.path("steps").get(2).path("state").asText()).isEqualTo("confirmed");
        assertThat(confirmed.path("steps").get(3).path("step").asInt()).isEqualTo(4);
        assertThat(confirmed.path("steps").get(3).path("state").asText()).isEqualTo("proposing");
        assertThat(bank()).isEqualTo(bankBefore + 2);
        assertThat(jdbc.queryForList("SELECT content FROM learning_hub.questions WHERE course_id = ? ORDER BY "
            + "question_id", String.class, ids[1])).containsExactly("Ai được mở tài khoản?",
            "Hồ sơ mở tài khoản gồm những gì?");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions WHERE course_id = ? AND "
            + "content = 'Giấy tờ nào bắt buộc?'", Integer.class, ids[1])).isZero();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT competency_id) FROM learning_hub.questions "
            + "WHERE course_id = ? AND competency_id = ?", Integer.class, ids[1], competency)).isEqualTo(1);

        // the new exam is a fixed final exam of the kept questions, selected to complete the course
        Map<String, Object> rule = jdbc.queryForMap("SELECT final_quiz_id, reward_points "
            + "FROM learning_hub.course_completion_rules WHERE revision_id = ?", ids[2]);
        assertThat(rule).containsEntry("reward_points", 45);
        long quizId = ((Number) rule.get("final_quiz_id")).longValue();
        assertThat(jdbc.queryForMap("SELECT course_id, quiz_type, selection_mode, question_count "
            + "FROM learning_hub.quizzes WHERE quiz_id = ?", quizId)).containsEntry("course_id", ids[1])
            .containsEntry("quiz_type", "FINAL_EXAM").containsEntry("selection_mode", "FIXED")
            .containsEntry("question_count", 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.quiz_questions WHERE quiz_id = ?",
            Integer.class, quizId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT ai_generated FROM learning_hub.course_revisions WHERE revision_id = ?",
            Boolean.class, ids[2])).isTrue();

        // confirmed once only
        api.error(INSTRUCTOR, confirm(ids[0], 3, rulesBody(ids[2], 45, competency, kept)), "CORE-VALIDATION_FAILED");
        assertThat(bank()).isEqualTo(bankBefore + 2);
    }

    @Test
    void keptQuestionsNeedACompetencyAndAProgrammeAndAFailedSaveLeavesNothingBehind() {
        long[] ids = rulesProposed("AI-RULES003");
        List<JsonNode> kept = List.of(api.ok(INSTRUCTOR, session(ids[0])).path("steps").get(2).path("proposal")
            .path("questions").get(0));
        int bankBefore = bank();

        JsonNode noCompetency = api.error(INSTRUCTOR, confirm(ids[0], 3, rulesBody(ids[2], 10, null, kept)),
            "CORE-VALIDATION_FAILED");
        assertThat(noCompetency.path("errors").get(0).path("field").asText()).isEqualTo("competencyId");
        Map<String, Object> noProgramme = rulesBody(ids[2], 10, competency, kept);
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) noProgramme.get("draft");
        draft.remove("programId");
        JsonNode refused = api.error(INSTRUCTOR, confirm(ids[0], 3, noProgramme), "CORE-VALIDATION_FAILED");
        assertThat(refused.path("errors").get(0).path("field").asText()).isEqualTo("draft.programId");
        // a competency that is not in the list: the bank refuses it and the draft save rolls back with it
        api.error(INSTRUCTOR, confirm(ids[0], 3, rulesBody(ids[2], 10, competency + 100_000, kept)),
            "ASSESSMENT-INVALID_REFERENCE");

        assertThat(bank()).isEqualTo(bankBefore);
        assertThat(finalExams(ids[1])).isZero();
        assertThat(jdbc.queryForObject("SELECT reward_points FROM learning_hub.course_completion_rules "
            + "WHERE revision_id = ?", Integer.class, ids[2])).isZero();
        JsonNode view = api.ok(INSTRUCTOR, session(ids[0]));
        assertThat(view.path("currentStep").asInt()).isEqualTo(3);
        assertThat(view.path("steps").get(2).path("state").asText()).isEqualTo("proposed");
    }

    @Test
    void removingEveryQuestionSavesTheRulesWithoutAnExam() {
        long[] ids = rulesProposed("AI-RULES004");
        int bankBefore = bank();

        JsonNode confirmed = api.ok(INSTRUCTOR, confirm(ids[0], 3, rulesBody(ids[2], 20, null, List.of())));

        assertThat(confirmed.path("currentStep").asInt()).isEqualTo(4);
        assertThat(bank()).isEqualTo(bankBefore);
        assertThat(finalExams(ids[1])).isZero();
        assertThat(jdbc.queryForObject("SELECT reward_points FROM learning_hub.course_completion_rules "
            + "WHERE revision_id = ?", Integer.class, ids[2])).isEqualTo(20);
    }

    // ---- step 4, audience and publish check (ticket 07) -----------------------------------------------------------------

    /** A session at step 4 with its audience proposed; returns {sessionId, courseId, revisionId}. */
    private long[] audienceProposed(String code) {
        long[] ids = rulesProposed(code);
        List<JsonNode> kept = List.of(api.ok(INSTRUCTOR, session(ids[0])).path("steps").get(2).path("proposal")
            .path("questions").get(0));
        api.ok(INSTRUCTOR, confirm(ids[0], 3, rulesBody(ids[2], 20, competency, kept)));
        assertThat(worker.runOnce()).isTrue();
        return ids;
    }

    @Test
    void confirmingStepFourEndsTheFlowAndAssignsSharesAndPublishesNothing() {
        long[] ids = audienceProposed("AI-AUDIENCE1");
        JsonNode proposed = api.ok(INSTRUCTOR, session(ids[0])).path("steps").get(3);
        assertThat(proposed.path("state").asText()).isEqualTo("proposed");
        assertThat(proposed.path("proposal").path("targets").get(0).path("orgId").asLong()).isEqualTo(ownOrg);
        assertThat(proposed.path("proposal").path("publishBlockers")).hasSize(1);
        Map<String, Object> before = courseState(ids[1], ids[2]);

        JsonNode confirmed = api.ok(INSTRUCTOR, confirm(ids[0], 4, Map.of()));

        assertThat(confirmed.path("status").asText()).isEqualTo("finished");
        assertThat(confirmed.path("currentStep").asInt()).isEqualTo(4);
        assertThat(confirmed.path("steps")).hasSize(4);
        assertThat(confirmed.path("steps").get(3).path("state").asText()).isEqualTo("confirmed");
        assertThat(courseState(ids[1], ids[2])).isEqualTo(before)
            .containsEntry("assignments", 0L).containsEntry("shares", 0L)
            .containsEntry("published_revision_id", null).containsEntry("state", "draft");
        // the flow is over: nothing more to confirm or propose
        api.error(INSTRUCTOR, confirm(ids[0], 4, Map.of()), "CORE-VALIDATION_FAILED");
        assertThat(worker.runOnce()).isFalse();
    }

    @Test
    void stepFourIsConfirmedOnlyOnceProposedAndOnlyByItsAuthor() {
        long[] ids = rulesProposed("AI-AUDIENCE2");
        api.ok(INSTRUCTOR, confirm(ids[0], 3, rulesBody(ids[2], 20, null, List.of())));

        api.error(INSTRUCTOR, confirm(ids[0], 4, Map.of()), "CORE-VALIDATION_FAILED");
        worker.runOnce();
        api.error(ADMIN, confirm(ids[0], 4, Map.of()), "CORE-RESOURCE_NOT_FOUND");
        api.http(LEARNER, confirm(ids[0], 4, Map.of()), 403, "CORE-ACCESS_DENIED");
        assertThat(api.ok(INSTRUCTOR, session(ids[0])).path("status").asText()).isEqualTo("active");
    }

    @Test
    void thePublishCheckOfADraftFromTheFlowNamesItsEmptyLessonsLikeThePage() {
        long[] ids = audienceProposed("AI-AUDIENCE3");

        // the saved draft has one empty lesson, a programme, a level and the step 3 exam (a draft, published with
        // the course): only the lessons block publishing
        assertThat(publishCheck.blockers(ids[2])).containsExactly(
            "Mọi bài học có nội dung, đủ tệp hoặc đã chọn bài kiểm tra", "1 bài chưa có nội dung");
    }

    @Test
    void theAudienceIsOfferedOnlyUnitsTheAuthorMayAssign() {
        long root = jdbc.queryForObject("""
            WITH RECURSIVE up(org_id, parent_id) AS (
                SELECT org_id, parent_id FROM learning_hub.orgs WHERE org_id = ?
                UNION SELECT o.org_id, o.parent_id FROM learning_hub.orgs o JOIN up ON o.org_id = up.parent_id)
            SELECT org_id FROM up WHERE parent_id IS NULL
            """, Long.class, ownOrg);

        List<DraftingTargets.Target> own = targets.assignable(INSTRUCTOR, ownOrg, 100);
        assertThat(own).anySatisfy(target -> {
            assertThat(target.targetType()).isEqualTo("org");
            assertThat(target.id()).isEqualTo(ownOrg);
            assertThat(target.withDescendants()).isTrue();
        });
        // the instructor manages their own unit only: from the top unit down, the top one is not offered
        List<DraftingTargets.Target> fromRoot = targets.assignable(INSTRUCTOR, root, 1000);
        assertThat(fromRoot).extracting(DraftingTargets.Target::id).contains(ownOrg);
        if (root != ownOrg) {
            assertThat(fromRoot).noneMatch(target -> "org".equals(target.targetType()) && target.id() == root);
        }
        // a learner may assign nothing
        assertThat(targets.assignable(LEARNER, ownOrg, 100)).isEmpty();
    }

    /** What step 4 must leave alone: the course's assignments, shares, published pointer and draft state. */
    private Map<String, Object> courseState(long courseId, long revisionId) {
        Map<String, Object> state = new HashMap<>(jdbc.queryForMap("""
            SELECT c.published_revision_id, r.state, r.ai_generated, r.row_version,
                   (SELECT count(*) FROM learning_hub.course_assignments a WHERE a.course_id = c.course_id)
                       AS assignments,
                   (SELECT count(*) FROM learning_hub.course_shares s WHERE s.course_id = c.course_id) AS shares
              FROM learning_hub.courses c JOIN learning_hub.course_revisions r ON r.revision_id = ?
             WHERE c.course_id = ?
            """, revisionId, courseId));
        return state;
    }

    /** What the page sends for step 3: the draft with the outline, the rules and the kept questions. */
    private Map<String, Object> rulesBody(long revisionId, int rewardPoints, Long competencyId, List<JsonNode> kept) {
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = new HashMap<>((Map<String, Object>) outlineBody(revisionId,
            lesson("Điều kiện mở tài khoản", List.of())).get("draft"));
        draft.put("programId", ownProgramme);
        draft.put("rewardPoints", rewardPoints);
        Map<String, Object> body = new HashMap<>();
        body.put("draft", draft);
        body.put("competencyId", competencyId);
        body.put("questions", kept);
        return body;
    }

    private int bank() {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions", Integer.class);
    }

    private int finalExams(long courseId) {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.quizzes WHERE course_id = ? "
            + "AND quiz_type = 'FINAL_EXAM'", Integer.class, courseId);
    }

    private Map<String, Object> lesson(String title, List<Object> blocks) {
        Map<String, Object> lesson = new HashMap<>();
        lesson.put("title", title);
        lesson.put("lessonType", "content");
        lesson.put("estimatedDurationSeconds", 0);
        lesson.put("isRequired", true);
        lesson.put("blocks", blocks);
        return lesson;
    }

    /** What the page sends for step 2: the draft as saved by step 1 with the outline's chapter. */
    private Map<String, Object> outlineBody(long revisionId, Object... lessons) {
        Map<String, Object> draft = new HashMap<>();
        draft.put("expectedVersion", jdbc.queryForObject(
            "SELECT row_version FROM learning_hub.course_revisions WHERE revision_id = ?", Long.class, revisionId));
        draft.put("title", "Mở tài khoản");
        draft.put("summary", "Quy trình.");
        draft.put("level", "basic");
        draft.put("defaultRequirement", "mandatory");
        draft.put("objectives", List.of("Thực hiện được"));
        draft.put("chapters", lessons.length == 0 ? List.of()
            : List.of(Map.of("title", "Chuẩn bị", "lessons", List.of(lessons))));
        draft.put("rewardPoints", 0);
        return Map.of("draft", draft);
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
