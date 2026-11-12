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
    static final String STOPPED = "stopped";

    public record JobRow(long id, String ownerUserId, long ownerOrgId, Long sourceDocumentId, String inputs,
                         String status, String step, int progress, String errorMessage, Long createdDraftId,
                         OffsetDateTime createTime, OffsetDateTime updateTime, Long courseId, Long revisionId) {
    }

    /** A job's source file that may be deleted. */
    public record ExpiredSource(long jobId, long documentId) {
    }

    public record StepRow(long id, long jobId, String kind, String itemKey, String inputHash, String output,
                          String status, int attempts, String errorMessage) {
    }

    private static final String JOB_COLUMNS = """
        job_id, owner_user_id, owner_org_id, source_document_id, inputs::text AS inputs, status, step, progress,
        error_message, created_draft_id, create_time, update_time, course_id, revision_id
        """;
    private static final RowMapper<JobRow> JOB = (rs, row) -> new JobRow(rs.getLong("job_id"),
        rs.getString("owner_user_id"), rs.getLong("owner_org_id"), (Long) rs.getObject("source_document_id"),
        rs.getString("inputs"), rs.getString("status"), rs.getString("step"), rs.getInt("progress"),
        rs.getString("error_message"), (Long) rs.getObject("created_draft_id"),
        rs.getObject("create_time", OffsetDateTime.class), rs.getObject("update_time", OffsetDateTime.class),
        (Long) rs.getObject("course_id"), (Long) rs.getObject("revision_id"));
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

    /** The job and the draft course it fills; created_draft_id mirrors course_id for clients of the older field. */
    public long insertJob(String ownerUserId, long ownerOrgId, Long sourceDocumentId, String inputsJson, long courseId,
                          long revisionId) {
        Long id = jdbc.queryForObject("""
            INSERT INTO learning_hub.course_generation_jobs (owner_user_id, owner_org_id, source_document_id, inputs,
                                                             status, step, course_id, revision_id, created_draft_id)
            VALUES (?, ?, ?, ?::jsonb, 'queued', 'summarise_sections', ?, ?, ?)
            RETURNING job_id
            """, Long.class, ownerUserId, ownerOrgId, sourceDocumentId, inputsJson, courseId, revisionId, courseId);

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
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM learning_hub.course_generation_jobs "
            + "WHERE job_id = ? AND deleted_at IS NULL", JOB, jobId).stream().findFirst();
    }

    /** Newest first. */
    public List<JobRow> jobsOf(String ownerUserId, int limit) {
        return jdbc.query("SELECT " + JOB_COLUMNS + """
             FROM learning_hub.course_generation_jobs WHERE owner_user_id = ? AND deleted_at IS NULL
             ORDER BY create_time DESC, job_id DESC LIMIT ?
            """, JOB, ownerUserId, limit);
    }

    /**
     * The owner's jobs that still work or wait for approval, plus the ones that failed or were stopped since the
     * instant: what the app shows as running or as a notice that lasts. Newest first.
     */
    public List<JobRow> activeJobsOf(String ownerUserId, OffsetDateTime stoppedSince, int limit) {
        return jdbc.query("SELECT " + JOB_COLUMNS + """
             FROM learning_hub.course_generation_jobs WHERE owner_user_id = ? AND deleted_at IS NULL
               AND (status IN ('queued', 'running', 'waiting_approval')
                    OR (status IN ('failed', 'stopped') AND update_time > ?))
             ORDER BY create_time DESC, job_id DESC LIMIT ?
            """, JOB, ownerUserId, stoppedSince, limit);
    }

    /** Create times of the owner's jobs since the instant, oldest first (cancelled, failed and deleted ones count too). */
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

    /** The revision of a course the job creates is flagged as AI generated, so publishing needs the review (ticket 22). */
    public void markAiGenerated(long revisionId) {
        jdbc.update("UPDATE learning_hub.course_revisions SET ai_generated = true WHERE revision_id = ?", revisionId);
    }

    /**
     * Whether a job is writing lessons into the revision now: its outline is approved (it has lesson steps) and it has
     * not finished, stopped or failed. Chapters and lessons of the revision may not change while this is true.
     */
    public boolean structureLocked(long revisionId) {
        Boolean locked = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM learning_hub.course_generation_jobs j
                            WHERE j.revision_id = ? AND j.status IN ('queued', 'running') AND j.deleted_at IS NULL
                              AND EXISTS (SELECT 1 FROM learning_hub.course_generation_steps s
                                           WHERE s.job_id = j.job_id AND s.kind = 'lesson'))
            """, Boolean.class, revisionId);

        return Boolean.TRUE.equals(locked);
    }

    public void setDraft(long jobId, long courseId) {
        jdbc.update("""
            UPDATE learning_hub.course_generation_jobs SET created_draft_id = ?, update_time = now() WHERE job_id = ?
            """, courseId, jobId);
    }

    /**
     * Removes the job for its owner but keeps the row, marked deleted and cancelled without its source document, so the
     * daily limit still counts it. The steps (and the model output in them) go.
     */
    public void deleteJob(long jobId) {
        jdbc.update("DELETE FROM learning_hub.course_generation_steps WHERE job_id = ?", jobId);
        jdbc.update("""
            UPDATE learning_hub.course_generation_jobs
               SET deleted_at = now(), source_document_id = NULL, inputs = '{}'::jsonb,
                   status = CASE WHEN status IN ('queued', 'running', 'waiting_approval') THEN 'cancelled' ELSE status END,
                   update_time = now()
             WHERE job_id = ?
            """, jobId);
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

    public record Named(long id, String name) {
    }

    /** Programmes whose name matches the text, ignoring case and spaces at both ends; at most 6. */
    public List<Named> programmesNamed(String text) {
        return jdbc.query("SELECT program_id, name FROM learning_hub.training_programs "
            + "WHERE lower(name) LIKE lower(?) ORDER BY (lower(name) = lower(?)) DESC, name LIMIT 6",
            (rs, row) -> new Named(rs.getLong(1), rs.getString(2)), "%" + text.strip() + "%", text.strip());
    }

    public List<Named> competenciesNamed(String text) {
        return jdbc.query("SELECT competency_id, name FROM learning_hub.competencies "
            + "WHERE NOT is_deleted AND lower(name) LIKE lower(?) "
            + "ORDER BY (lower(name) = lower(?)) DESC, name LIMIT 6",
            (rs, row) -> new Named(rs.getLong(1), rs.getString(2)), "%" + text.strip() + "%", text.strip());
    }

    /** Sources of jobs that ended (finished, stopped, failed or cancelled) before {@code cutoff}. */
    public List<ExpiredSource> expiredSources(OffsetDateTime cutoff) {
        return jdbc.query("""
            SELECT job_id, source_document_id FROM learning_hub.course_generation_jobs
             WHERE source_document_id IS NOT NULL AND status IN ('completed', 'failed', 'cancelled', 'stopped')
               AND update_time < ?
            """, (rs, row) -> new ExpiredSource(rs.getLong("job_id"), rs.getLong("source_document_id")), cutoff);
    }

    /** The job no longer has a source file; its steps keep what they wrote. */
    public void clearSource(long jobId) {
        jdbc.update("UPDATE learning_hub.course_generation_jobs SET source_document_id = NULL, update_time = now() "
            + "WHERE job_id = ?", jobId);
    }

    public Optional<DocumentInfo> document(long documentId) {
        return Optional.ofNullable(documents(List.of(documentId)).get(documentId));
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

    public Optional<StepRow> stepByKey(long jobId, String kind, String itemKey) {
        return jdbc.query("SELECT " + STEP_COLUMNS + " FROM learning_hub.course_generation_steps "
            + "WHERE job_id = ? AND kind = ? AND item_key = ?", STEP, jobId, kind, itemKey).stream().findFirst();
    }

    public List<StepRow> stepsOfKind(long jobId, String kind) {
        return jdbc.query("SELECT " + STEP_COLUMNS + " FROM learning_hub.course_generation_steps "
            + "WHERE job_id = ? AND kind = ? ORDER BY step_id", STEP, jobId, kind);
    }

    /** Serialises the changes of one job (an edit, an approval, a decision) for the rest of the transaction. */
    public Optional<JobRow> lockJob(long jobId) {
        return jdbc.query("SELECT " + JOB_COLUMNS + " FROM learning_hub.course_generation_jobs WHERE job_id = ? "
            + "AND deleted_at IS NULL FOR UPDATE", JOB, jobId).stream().findFirst();
    }

    /** Replaces the stored output of a finished step (the author's edit or a decision); never touches its status. */
    public void setOutput(long stepId, String outputJson) {
        jdbc.update("UPDATE learning_hub.course_generation_steps SET output = ?::jsonb WHERE step_id = ?", outputJson,
            stepId);
    }

    /** A finished or failed step runs again; its old output is dropped so nothing stale is read meanwhile. */
    public boolean resetStep(long stepId) {
        return jdbc.update("""
            UPDATE learning_hub.course_generation_steps
               SET status = 'pending', output = NULL, error_message = NULL, finished_at = NULL
             WHERE step_id = ? AND status IN ('done', 'failed')
            """, stepId) == 1;
    }

    /** The questions of a lesson that is written again are proposed again; a missing or running step is left as it is. */
    public void resetStepIfDone(long jobId, String kind, String itemKey) {
        jdbc.update("""
            UPDATE learning_hub.course_generation_steps
               SET status = 'pending', output = NULL, error_message = NULL, finished_at = NULL
             WHERE job_id = ? AND kind = ? AND item_key = ? AND status IN ('done', 'failed')
            """, jobId, kind, itemKey);
    }

    /** Failed steps that have run fewer than the attempts run again (the author pressed retry). */
    public int requeueFailed(long jobId, int maxAttempts) {
        return jdbc.update("""
            UPDATE learning_hub.course_generation_steps SET status = 'pending', error_message = NULL, finished_at = NULL
             WHERE job_id = ? AND status = 'failed' AND attempts < ?
            """, jobId, maxAttempts);
    }

    /** A step the source was not ready for goes back without costing an attempt. */
    public void releaseStep(long stepId) {
        jdbc.update("""
            UPDATE learning_hub.course_generation_steps SET status = 'pending', attempts = GREATEST(attempts - 1, 0)
             WHERE step_id = ? AND status = 'running'
            """, stepId);
    }

    /**
     * Puts a job waiting for the author, one that failed, one that finished or one that was stopped back to work (the
     * author asked for a lesson again); a cancelled job stays.
     */
    public boolean reopen(long jobId, String step, int progress) {
        return jdbc.update("""
            UPDATE learning_hub.course_generation_jobs
               SET status = 'running', step = ?, progress = ?, error_message = NULL, update_time = now()
             WHERE job_id = ? AND status IN ('waiting_approval', 'failed', 'completed', 'stopped')
            """, step, progress, jobId) == 1;
    }

    public long countSteps(long jobId, String kind, String status) {
        Long count = jdbc.queryForObject("""
            SELECT count(*) FROM learning_hub.course_generation_steps
             WHERE job_id = ? AND kind = ? AND (?::text IS NULL OR status = ?)
            """, Long.class, jobId, kind, status, status);

        return count == null ? 0 : count;
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
