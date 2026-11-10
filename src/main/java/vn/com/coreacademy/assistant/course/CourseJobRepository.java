package vn.com.coreacademy.assistant.course;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The tables of course generation, as a queue of steps. Every transition is one statement guarded by the status it
 * expects, so a stray call cannot overwrite a finished step. Claiming takes the advisory lock first: that is what makes
 * "one job runs at a time on the whole server" true with several instances.
 */
@Repository
@RequiredArgsConstructor
public class CourseJobRepository {
    /** Held for the length of the claiming transaction only. */
    private static final long CLAIM_LOCK = 7_214_901_113L;

    static final String QUEUED = "queued";
    static final String RUNNING = "running";
    static final String WAITING_APPROVAL = "waiting_approval";
    static final String COMPLETED = "completed";
    static final String FAILED = "failed";
    static final String CANCELLED = "cancelled";

    public record JobRow(long id, String ownerUserId, long ownerOrgId, Long sourceDocumentId, String inputs,
                         String status, String step, int progress, String errorMessage, Long createdDraftId,
                         OffsetDateTime createTime, OffsetDateTime updateTime) {
    }

    public record StepRow(long id, long jobId, String kind, String itemKey, String inputHash, String output,
                          String status, int attempts, String errorMessage) {
    }

    private static final String JOB_COLUMNS = """
        job_id, owner_user_id, owner_org_id, source_document_id, inputs::text AS inputs, status, step, progress,
        error_message, created_draft_id, create_time, update_time
        """;
    private static final RowMapper<JobRow> JOB = (rs, row) -> new JobRow(rs.getLong("job_id"),
        rs.getString("owner_user_id"), rs.getLong("owner_org_id"), (Long) rs.getObject("source_document_id"),
        rs.getString("inputs"), rs.getString("status"), rs.getString("step"), rs.getInt("progress"),
        rs.getString("error_message"), (Long) rs.getObject("created_draft_id"),
        rs.getObject("create_time", OffsetDateTime.class), rs.getObject("update_time", OffsetDateTime.class));
    private static final String STEP_COLUMNS = """
        step_id, job_id, kind, item_key, input_hash, output::text AS output, status, attempts, error_message
        """;
    private static final RowMapper<StepRow> STEP = (rs, row) -> new StepRow(rs.getLong("step_id"),
        rs.getLong("job_id"), rs.getString("kind"), rs.getString("item_key"), rs.getString("input_hash"),
        rs.getString("output"), rs.getString("status"), rs.getInt("attempts"), rs.getString("error_message"));

    private final JdbcTemplate jdbc;

    // ---- jobs ------------------------------------------------------------------------------------------------------

    /** Serialises the limit check and the insert of one user's jobs. Must run inside the inserting transaction. */
    public void lockOwner(String ownerUserId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, "course-job:" + ownerUserId);
    }

    public long insertJob(String ownerUserId, long ownerOrgId, Long sourceDocumentId, String inputsJson) {
        Long id = jdbc.queryForObject("""
            INSERT INTO learning_hub.course_generation_jobs (owner_user_id, owner_org_id, source_document_id, inputs,
                                                             status, step)
            VALUES (?, ?, ?, ?::jsonb, 'queued', 'summarise_sections')
            RETURNING job_id
            """, Long.class, ownerUserId, ownerOrgId, sourceDocumentId, inputsJson);

        return id;
    }

    public void insertStep(long jobId, String kind, String itemKey) {
        jdbc.update("""
            INSERT INTO learning_hub.course_generation_steps (job_id, kind, item_key, status)
            VALUES (?, ?, ?, 'pending')
            ON CONFLICT (job_id, kind, item_key) DO NOTHING
            """, jobId, kind, itemKey);
    }

    public Optional<JobRow> job(long jobId) {
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM learning_hub.course_generation_jobs WHERE job_id = ?", JOB,
            jobId).stream().findFirst();
    }

    /** Newest first. */
    public List<JobRow> jobsOf(String ownerUserId, int limit) {
        return jdbc.query("SELECT " + JOB_COLUMNS + """
             FROM learning_hub.course_generation_jobs WHERE owner_user_id = ?
             ORDER BY create_time DESC, job_id DESC LIMIT ?
            """, JOB, ownerUserId, limit);
    }

    /** Create times of the owner's jobs since the instant, oldest first (cancelled and failed ones count too). */
    public List<OffsetDateTime> createdSince(String ownerUserId, OffsetDateTime since) {
        return jdbc.queryForList("""
            SELECT create_time FROM learning_hub.course_generation_jobs
             WHERE owner_user_id = ? AND create_time > ? ORDER BY create_time
            """, OffsetDateTime.class, ownerUserId, since);
    }

    public void setProgress(long jobId, String step, int progress) {
        jdbc.update("""
            UPDATE learning_hub.course_generation_jobs SET step = ?, progress = ?, update_time = now()
             WHERE job_id = ? AND status IN ('queued', 'running', 'waiting_approval')
            """, step, progress, jobId);
    }

    /** Moves a live job to the status; a finished job (completed, failed, cancelled) is never reopened. */
    public boolean setStatus(long jobId, String status, String errorMessage) {
        return jdbc.update("""
            UPDATE learning_hub.course_generation_jobs SET status = ?, error_message = ?, update_time = now()
             WHERE job_id = ? AND status IN ('queued', 'running', 'waiting_approval')
            """, status, errorMessage, jobId) == 1;
    }

    public void setDraft(long jobId, long courseId) {
        jdbc.update("""
            UPDATE learning_hub.course_generation_jobs SET created_draft_id = ?, update_time = now() WHERE job_id = ?
            """, courseId, jobId);
    }

    public void deleteJob(long jobId) {
        jdbc.update("DELETE FROM learning_hub.course_generation_jobs WHERE job_id = ?", jobId);
    }

    public record DocumentInfo(long id, String fileName, String status) {
    }

    /** File name and processing status of the source documents, by document id. */
    public java.util.Map<Long, DocumentInfo> documents(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, DocumentInfo> found = new java.util.HashMap<>();
        String marks = String.join(", ", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.query("SELECT document_id, file_name, status FROM learning_hub.knowledge_documents "
            + "WHERE document_id IN (" + marks + ")", rs -> {
            found.put(rs.getLong("document_id"), new DocumentInfo(rs.getLong("document_id"),
                rs.getString("file_name"), rs.getString("status")));
        }, ids.toArray());

        return found;
    }

    public boolean programmeExists(long programmeId) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM learning_hub.training_programs WHERE program_id = ?", Integer.class, programmeId);

        return n != null && n > 0;
    }

    public boolean competencyExists(long competencyId) {
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM learning_hub.competencies WHERE competency_id = ? AND NOT is_deleted
            """, Integer.class, competencyId);

        return n != null && n > 0;
    }

    // ---- steps -----------------------------------------------------------------------------------------------------

    public List<StepRow> steps(long jobId) {
        return jdbc.query("SELECT " + STEP_COLUMNS + " FROM learning_hub.course_generation_steps WHERE job_id = ? "
            + "ORDER BY step_id", STEP, jobId);
    }

    public Optional<StepRow> step(long stepId) {
        return jdbc.query("SELECT " + STEP_COLUMNS + " FROM learning_hub.course_generation_steps WHERE step_id = ?",
            STEP, stepId).stream().findFirst();
    }

    /**
     * Takes the oldest pending step of the job that may run now and marks both it and its job running. A job may run
     * when it is already running or when no job is. Callers run this in a transaction: the advisory lock it takes ends
     * with it, after the job is marked, so a second instance sees the first one's job running.
     */
    public Optional<StepRow> claimNext() {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, CLAIM_LOCK);
        List<StepRow> claimed = jdbc.query("""
            UPDATE learning_hub.course_generation_steps s
               SET status = 'running', started_at = now(), attempts = s.attempts + 1
             WHERE s.step_id = (SELECT st.step_id
                                  FROM learning_hub.course_generation_steps st
                                  JOIN learning_hub.course_generation_jobs j ON j.job_id = st.job_id
                                 WHERE st.status = 'pending' AND j.status IN ('queued', 'running')
                                   AND (j.status = 'running' OR NOT EXISTS (
                                        SELECT 1 FROM learning_hub.course_generation_jobs r WHERE r.status = 'running'))
                                 ORDER BY j.create_time, j.job_id, st.step_id
                                 FOR UPDATE OF st SKIP LOCKED
                                 LIMIT 1)
            RETURNING """ + " " + STEP_COLUMNS, STEP);
        claimed.forEach(step -> jdbc.update("""
            UPDATE learning_hub.course_generation_jobs SET status = 'running', step = ?, update_time = now()
             WHERE job_id = ? AND status IN ('queued', 'running')
            """, step.kind(), step.jobId()));

        return claimed.stream().findFirst();
    }

    public void completeStep(long stepId, String outputJson, String inputHash) {
        jdbc.update("""
            UPDATE learning_hub.course_generation_steps
               SET status = 'done', output = ?::jsonb, input_hash = ?, error_message = NULL, finished_at = now()
             WHERE step_id = ? AND status = 'running'
            """, outputJson, inputHash, stepId);
    }

    public void failStep(long stepId, String errorMessage) {
        jdbc.update("""
            UPDATE learning_hub.course_generation_steps
               SET status = 'failed', error_message = ?, finished_at = now()
             WHERE step_id = ? AND status = 'running'
            """, errorMessage, stepId);
    }

    /** A step that ran when the server stopped goes back to pending; finished steps keep their output. */
    public int requeueInterrupted() {
        return jdbc.update("UPDATE learning_hub.course_generation_steps SET status = 'pending' WHERE status = 'running'");
    }

    /** Whether the job has a step that still has to run (pending or running). */
    public boolean hasOpenSteps(long jobId) {
        Integer open = jdbc.queryForObject("""
            SELECT count(*) FROM learning_hub.course_generation_steps
             WHERE job_id = ? AND status IN ('pending', 'running')
            """, Integer.class, jobId);

        return open != null && open > 0;
    }
}
