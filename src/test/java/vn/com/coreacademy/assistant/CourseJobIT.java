package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.assistant.course.CourseGenerationWorker;
import vn.com.coreacademy.assistant.course.CourseJobRepository.StepRow;
import vn.com.coreacademy.assistant.course.GenerationStepRunner;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.NextStep;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.Result;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static vn.com.coreacademy.assessment.support.ApiClient.delete;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.REPORT_VIEWER;

/** Starting, queueing, limiting and resuming course generation jobs; the model work itself is a stub here. */
class CourseJobIT extends AssessmentIntegrationTest {
    private static final String JOBS = "/api/v1/assistant/course-jobs";
    private static final String ORG_SCOPED_ADMIN = "000003";

    @MockitoBean
    private OllamaClient ollama;
    @MockitoBean
    private GenerationStepRunner runner;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CourseGenerationWorker worker;

    private ApiClient api;
    private long ownOrg;
    private long otherOrg;
    private long programme;
    private long otherProgramme;
    private long competency;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        fixtures.course();
        competency = fixtures.competency();
        ownOrg = fixtures.orgId();
        programme = jdbc.queryForObject("SELECT min(program_id) FROM learning_hub.training_programs", Long.class);
        jdbc.update("""
            INSERT INTO learning_hub.orgs(parent_id, org_type, code, name, status, sort_no)
            SELECT NULL, 'UNIT', 'COURSEJOB-OTHER', 'Đơn vị khác', 'active', 9
             WHERE NOT EXISTS (SELECT 1 FROM learning_hub.orgs WHERE code = 'COURSEJOB-OTHER')
            """);
        otherOrg = jdbc.queryForObject("SELECT org_id FROM learning_hub.orgs WHERE code = 'COURSEJOB-OTHER'",
            Long.class);
        // A course is made with its job, and a field and a programme belong to one unit: the other unit needs its own.
        jdbc.update("""
            INSERT INTO learning_hub.training_fields(owner_org_id, code, name, status, sort_no, created_by, updated_by)
            SELECT ?, 'COURSEJOB-FIELD', 'Lĩnh vực đơn vị khác', 'active', 9, '000001', '000001'
             WHERE NOT EXISTS (SELECT 1 FROM learning_hub.training_fields WHERE code = 'COURSEJOB-FIELD')
            """, otherOrg);
        jdbc.update("""
            INSERT INTO learning_hub.training_programs(field_id, owner_org_id, code, name, status, sort_no,
                                                       created_by, updated_by)
            SELECT f.field_id, ?, 'COURSEJOB-PROG', 'Chương trình đơn vị khác', 'active', 9, '000001', '000001'
              FROM learning_hub.training_fields f WHERE f.code = 'COURSEJOB-FIELD'
               AND NOT EXISTS (SELECT 1 FROM learning_hub.training_programs WHERE code = 'COURSEJOB-PROG')
            """, otherOrg);
        otherProgramme = jdbc.queryForObject(
            "SELECT program_id FROM learning_hub.training_programs WHERE code = 'COURSEJOB-PROG'", Long.class);
    }

    @BeforeEach
    void freshQueue() {
        when(ollama.isAvailable()).thenReturn(true);
        // The stub: a summary leads to an outline, and the outline waits for approval, which frees the server.
        when(runner.run(any())).thenAnswer(invocation -> {
            StepRow step = invocation.getArgument(0);
            // Stubbing again calls the earlier answer once, with no arguments.
            if (step == null) {
                return null;
            }
            return step.kind().equals("summarise_sections")
                ? new Result("{\"sections\":[]}", "h1", List.of(new NextStep("outline", "")), 20, null)
                : new Result("{\"chapters\":[]}", "h2", List.of(), 40, "waiting_approval");
        });
        jdbc.update("DELETE FROM learning_hub.course_generation_jobs");
        // Drafting sessions count against the same daily allowance.
        jdbc.update("DELETE FROM learning_hub.course_drafting_sessions");
    }

    private Map<String, Object> inputs(Long ownerOrgId) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("targetLearners", "Giao dịch viên mới vào làm");
        inputs.put("level", "BEGINNER");
        inputs.put("chapters", 3);
        inputs.put("programmeId", ownerOrgId != null && ownerOrgId == otherOrg ? otherProgramme : programme);
        inputs.put("competencyId", competency);
        inputs.put("ownerOrgId", ownerOrgId);
        return inputs;
    }

    private MockHttpServletRequestBuilder start(Map<String, Object> inputs) {
        try {
            return multipart(JOBS)
                .file(new MockMultipartFile("file", "quy-trinh.md", "text/markdown",
                    "# Quy trình\n\nNội dung.\n".getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("inputs", "", "application/json", json.writeValueAsBytes(inputs)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long create(String user, Long ownerOrgId) {
        return api.ok(user, start(inputs(ownerOrgId))).path("jobId").asLong();
    }

    private String status(long jobId, String user) {
        return api.ok(user, get(JOBS + "/{id}", jobId)).path("status").asText();
    }

    @Test
    void learnersAndReportViewersAreRefused() {
        api.http(LEARNER, start(inputs(null)), 403, "CORE-ACCESS_DENIED");
        api.http(REPORT_VIEWER, start(inputs(null)), 403, "CORE-ACCESS_DENIED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_generation_jobs", Integer.class))
            .isZero();
    }

    @Test
    void anInstructorGetsTheirOwnUnitAndCannotChooseAnother() {
        JsonNode job = api.ok(INSTRUCTOR, start(inputs(null)));

        assertThat(job.path("ownerOrgId").asLong()).isEqualTo(ownOrg);
        assertThat(job.path("status").asText()).isEqualTo("queued");
        assertThat(job.path("source").path("fileName").asText()).isEqualTo("quy-trinh.md");
        assertThat(job.path("steps").get(0).path("kind").asText()).isEqualTo("summarise_sections");
        assertThat(job.path("inputs").path("level").asText()).isEqualTo("BEGINNER");
        api.http(INSTRUCTOR, start(inputs(otherOrg)), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void anAdminChoosesAUnitInsideTheirScopeOnly() {
        api.error(ADMIN, start(inputs(null)), "CORE-VALIDATION_FAILED");
        assertThat(api.ok(ADMIN, start(inputs(otherOrg))).path("ownerOrgId").asLong()).isEqualTo(otherOrg);

        assertThat(api.ok(ORG_SCOPED_ADMIN, start(inputs(ownOrg))).path("ownerOrgId").asLong()).isEqualTo(ownOrg);
        api.http(ORG_SCOPED_ADMIN, start(inputs(otherOrg)), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void theSourceIsACourseSourceDocumentNobodyElseCanSeeAndJobsOfOthersAreNotFound() {
        JsonNode job = api.ok(INSTRUCTOR, start(inputs(null)));
        long documentId = job.path("source").path("documentId").asLong();

        assertThat(jdbc.queryForMap("SELECT scope, uploaded_by FROM learning_hub.knowledge_documents "
            + "WHERE document_id = ?", documentId)).containsEntry("scope", "course_source")
            .containsEntry("uploaded_by", INSTRUCTOR);
        api.error(ADMIN, get("/api/v1/knowledge/documents/{id}", documentId), "CORE-RESOURCE_NOT_FOUND");
        api.error(ADMIN, get(JOBS + "/{id}", job.path("jobId").asLong()), "CORE-RESOURCE_NOT_FOUND");
        assertThat(api.ok(ADMIN, get(JOBS))).isEmpty();
        assertThat(api.ok(INSTRUCTOR, get(JOBS))).hasSize(1);
    }

    @Test
    void theFourthJobOfTheDayIsRefusedWithTheWaitTime() {
        create(INSTRUCTOR, null);
        create(INSTRUCTOR, null);
        create(INSTRUCTOR, null);

        int documentsBefore = jdbc.queryForObject("SELECT count(*) FROM learning_hub.knowledge_documents",
            Integer.class);

        JsonNode refused = api.error(INSTRUCTOR, start(inputs(null)), "ASSISTANT-JOB_RATE_LIMITED");

        assertThat(refused.path("errors").get(0).path("message").asText()).contains("thử lại sau").contains("giờ");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_generation_jobs", Integer.class))
            .isEqualTo(3);
        // the refused request stored no document
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.knowledge_documents", Integer.class))
            .isEqualTo(documentsBefore);
        // other people have their own allowance
        create(ADMIN, ownOrg);
    }

    @Test
    void deletingJobsDoesNotGiveTheAllowanceBackButTheyLeaveTheList() {
        for (int i = 0; i < 3; i++) {
            long job = create(INSTRUCTOR, null);
            api.ok(INSTRUCTOR, delete(JOBS + "/{id}", job));
            api.error(INSTRUCTOR, get(JOBS + "/{id}", job), "CORE-RESOURCE_NOT_FOUND");
        }

        assertThat(api.ok(INSTRUCTOR, get(JOBS))).isEmpty();
        api.error(INSTRUCTOR, start(inputs(null)), "ASSISTANT-JOB_RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_generation_steps",
            Integer.class)).isZero();
    }

    @Test
    void aSecondJobWaitsForTheFirstAndStartsOnceItIsWaitingForApproval() {
        long first = create(INSTRUCTOR, null);
        long second = create(ADMIN, ownOrg);

        assertThat(worker.runOnce()).isTrue();
        assertThat(status(first, INSTRUCTOR)).isEqualTo("running");
        assertThat(worker.runOnce()).isTrue();
        // the first job's outline ran, not the second job's first step
        assertThat(status(first, INSTRUCTOR)).isEqualTo("waiting_approval");
        assertThat(status(second, ADMIN)).isEqualTo("queued");

        assertThat(worker.runOnce()).isTrue();
        assertThat(status(second, ADMIN)).isEqualTo("running");
        assertThat(worker.runOnce()).isTrue();
        assertThat(worker.runOnce()).isFalse();
        assertThat(status(second, ADMIN)).isEqualTo("waiting_approval");
    }

    @Test
    void aRestartResumesARunningJobFromItsLastFinishedStep() {
        long job = create(INSTRUCTOR, null);
        assertThat(worker.runOnce()).isTrue();
        // the outline step was running when the server stopped
        jdbc.update("UPDATE learning_hub.course_generation_steps SET status = 'running' WHERE job_id = ? "
            + "AND kind = 'outline'", job);

        worker.requeueInterrupted();

        List<Map<String, Object>> steps = jdbc.queryForList(
            "SELECT kind, status, output::text AS output FROM learning_hub.course_generation_steps "
                + "WHERE job_id = ? ORDER BY step_id", job);
        assertThat(steps).extracting(step -> step.get("kind") + ":" + step.get("status"))
            .containsExactly("summarise_sections:done", "outline:pending");
        assertThat(steps.get(0).get("output").toString()).contains("sections");
        assertThat(worker.runOnce()).isTrue();
        assertThat(status(job, INSTRUCTOR)).isEqualTo("waiting_approval");
    }

    @Test
    void aFailedStepFailsTheJobWithAReadableError() {
        when(runner.run(any())).thenThrow(new GenerationStepRunner.GenerationException("Tài liệu quá ngắn để tạo khóa."));
        long job = create(INSTRUCTOR, null);

        worker.runOnce();

        JsonNode view = api.ok(INSTRUCTOR, get(JOBS + "/{id}", job));
        assertThat(view.path("status").asText()).isEqualTo("failed");
        assertThat(view.path("error").asText()).isEqualTo("Tài liệu quá ngắn để tạo khóa.");
        assertThat(worker.runOnce()).isFalse();
    }

    @Test
    void theActiveListHoldsRunningAndRecentlyFailedOrStoppedJobsWithTheirCourse() {
        long failed = create(INSTRUCTOR, null);
        long stopped = create(INSTRUCTOR, null);
        long old = create(INSTRUCTOR, null);
        jdbc.update("UPDATE learning_hub.course_generation_jobs SET status = 'failed', error_message = 'x' "
            + "WHERE job_id = ?", failed);
        jdbc.update("UPDATE learning_hub.course_generation_jobs SET status = 'stopped' WHERE job_id = ?", stopped);
        jdbc.update("UPDATE learning_hub.course_generation_jobs SET status = 'failed', "
            + "update_time = now() - interval '5 days' WHERE job_id = ?", old);

        JsonNode mine = api.ok(INSTRUCTOR, get(JOBS + "/active"));
        List<Long> ids = new java.util.ArrayList<>();
        mine.forEach(item -> ids.add(item.path("jobId").asLong()));

        assertThat(ids).contains(failed, stopped).doesNotContain(old);
        mine.forEach(item -> assertThat(item.path("courseId").asLong()).isPositive());
        // Only the caller's own jobs: a learner may use the assistant but owns none.
        assertThat(api.ok(LEARNER, get(JOBS + "/active"))).isEmpty();
    }

    @Test
    void cancelStopsAJobAndDeleteRemovesItWithItsSourceDocument() {
        JsonNode created = api.ok(INSTRUCTOR, start(inputs(null)));
        long job = created.path("jobId").asLong();
        long documentId = created.path("source").path("documentId").asLong();

        assertThat(api.ok(INSTRUCTOR, api.post(Map.of(), JOBS + "/{id}/cancel", job)).path("status").asText())
            .isEqualTo("cancelled");
        assertThat(worker.runOnce()).isFalse();
        api.error(INSTRUCTOR, api.post(Map.of(), JOBS + "/{id}/cancel", job), "CORE-VALIDATION_FAILED");

        api.ok(INSTRUCTOR, delete(JOBS + "/{id}", job));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_generation_jobs "
            + "WHERE job_id = ? AND deleted_at IS NOT NULL AND source_document_id IS NULL", Integer.class, job))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_generation_steps WHERE job_id = ?",
            Integer.class, job)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.knowledge_documents WHERE document_id = ?",
            Integer.class, documentId)).isZero();
    }

    @Test
    void aRunningJobMustBeCancelledBeforeItIsDeleted() {
        long job = create(INSTRUCTOR, null);
        worker.runOnce();

        api.error(INSTRUCTOR, delete(JOBS + "/{id}", job), "CORE-VALIDATION_FAILED");
    }

    @Test
    void anInvalidFormIsRefusedBeforeAnythingIsStored() {
        Map<String, Object> bad = inputs(null);
        bad.put("level", "EXPERT");
        api.error(INSTRUCTOR, start(bad), "CORE-VALIDATION_FAILED");
        Map<String, Object> noSize = inputs(null);
        noSize.remove("chapters");
        api.error(INSTRUCTOR, start(noSize), "CORE-VALIDATION_FAILED");
        Map<String, Object> noProgramme = inputs(null);
        noProgramme.put("programmeId", -1L);
        api.error(INSTRUCTOR, start(noProgramme), "CORE-VALIDATION_FAILED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_generation_jobs", Integer.class))
            .isZero();
    }
}
