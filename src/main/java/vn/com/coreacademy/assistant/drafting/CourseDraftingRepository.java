package vn.com.coreacademy.assistant.drafting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * The drafting sessions and their steps. A step in {@code proposing} with no {@code claimed_at} waits for the worker;
 * every transition is one statement guarded by the state it expects, so a stray call cannot overwrite a later state.
 */
@Repository
@RequiredArgsConstructor
public class CourseDraftingRepository {
    public record SessionRow(long id, String ownerUserId, long ownerOrgId, Long sourceDocumentId, Long conversationId,
                             Long courseId, Long revisionId, int currentStep, String status, String errorMessage,
                             String hints, String sourceSummary, OffsetDateTime createTime,
                             OffsetDateTime updateTime) {
    }

    public record DraftingStepRow(long id, long sessionId, int stepNo, String state, String proposal, String notes,
                                  String errorMessage, int attempts, OffsetDateTime claimedAt, boolean stopped,
                                  OffsetDateTime updateTime) {
    }

    /** A programme or module the author may pick, with the field it belongs to. */
    public record Candidate(long id, String name, long fieldId) {
    }

    public record DocumentInfo(long id, String fileName, String status) {
    }

    private static final String SESSION_COLUMNS = """
        session_id, owner_user_id, owner_org_id, source_document_id, conversation_id, course_id, revision_id,
        current_step, status, error_message, hints::text AS hints, source_summary::text AS source_summary,
        create_time, update_time
        """;
    private static final RowMapper<SessionRow> SESSION = (rs, row) -> new SessionRow(rs.getLong("session_id"),
        rs.getString("owner_user_id"), rs.getLong("owner_org_id"), (Long) rs.getObject("source_document_id"),
        (Long) rs.getObject("conversation_id"), (Long) rs.getObject("course_id"), (Long) rs.getObject("revision_id"),
        rs.getInt("current_step"), rs.getString("status"), rs.getString("error_message"), rs.getString("hints"),
        rs.getString("source_summary"), rs.getObject("create_time", OffsetDateTime.class),
        rs.getObject("update_time", OffsetDateTime.class));
    private static final String STEP_COLUMNS = """
        step_id, session_id, step_no, state, proposal::text AS proposal, notes::text AS notes, error_message,
        attempts, claimed_at, stopped, update_time
        """;
    private static final RowMapper<DraftingStepRow> STEP = (rs, row) -> new DraftingStepRow(rs.getLong("step_id"),
        rs.getLong("session_id"), rs.getInt("step_no"), rs.getString("state"), rs.getString("proposal"),
        rs.getString("notes"), rs.getString("error_message"), rs.getInt("attempts"),
        rs.getObject("claimed_at", OffsetDateTime.class), rs.getBoolean("stopped"),
        rs.getObject("update_time", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    // ---- sessions --------------------------------------------------------------------------------------------------

    public long insertSession(String ownerUserId, long ownerOrgId, long sourceDocumentId, Long conversationId,
                              String hintsJson) {
        return jdbc.queryForObject("""
            INSERT INTO learning_hub.course_drafting_sessions (owner_user_id, owner_org_id, source_document_id,
                                                               conversation_id, current_step, status, hints)
            VALUES (?, ?, ?, ?, 1, 'active', ?::jsonb)
            RETURNING session_id
            """, Long.class, ownerUserId, ownerOrgId, sourceDocumentId, conversationId, hintsJson);
    }

    public Optional<SessionRow> session(long sessionId) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM learning_hub.course_drafting_sessions "
            + "WHERE session_id = ?", SESSION, sessionId).stream().findFirst();
    }

    /**
     * Start times of the user's course generation since the instant, oldest first: old jobs (deleted ones too) and
     * drafting sessions count against the same daily allowance.
     */
    public List<OffsetDateTime> startedSince(String ownerUserId, OffsetDateTime since) {
        return jdbc.queryForList("""
            SELECT started
              FROM (SELECT create_time AS started
                      FROM learning_hub.course_generation_jobs
                     WHERE owner_user_id = ? AND create_time > ?
                    UNION ALL
                    SELECT create_time
                      FROM learning_hub.course_drafting_sessions
                     WHERE owner_user_id = ? AND create_time > ?) starts
             ORDER BY started
            """, OffsetDateTime.class, ownerUserId, since, ownerUserId, since);
    }

    /** Stores the summary of the source once; every later step and proposal reads it instead of the document. */
    public void setSourceSummary(long sessionId, String summaryJson) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions SET source_summary = ?::jsonb, update_time = now()
             WHERE session_id = ?
            """, summaryJson, sessionId);
    }

    /** Ends an active session as failed with a message the chat and the page can show. */
    public void failSession(long sessionId, String errorMessage) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions SET status = 'failed', error_message = ?, update_time = now()
             WHERE session_id = ? AND status = 'active'
            """, errorMessage, sessionId);
    }

    public void touchSession(long sessionId) {
        jdbc.update("UPDATE learning_hub.course_drafting_sessions SET update_time = now() WHERE session_id = ?",
            sessionId);
    }

    // ---- steps -----------------------------------------------------------------------------------------------------

    /** The step starts being proposed; the worker picks it up. */
    public void insertStep(long sessionId, int stepNo) {
        jdbc.update("""
            INSERT INTO learning_hub.course_drafting_steps (session_id, step_no, state)
            VALUES (?, ?, 'proposing')
            """, sessionId, stepNo);
    }

    /** The steps reached, ascending. */
    public List<DraftingStepRow> steps(long sessionId) {
        return jdbc.query("SELECT " + STEP_COLUMNS + " FROM learning_hub.course_drafting_steps WHERE session_id = ? "
            + "ORDER BY step_no", STEP, sessionId);
    }

    /**
     * Takes the oldest waiting step of an active session and marks it claimed. Callers run this in a transaction;
     * {@code SKIP LOCKED} lets another instance take another step meanwhile.
     */
    public Optional<DraftingStepRow> claimNext() {
        return jdbc.query("""
            UPDATE learning_hub.course_drafting_steps s
               SET claimed_at = now(), attempts = s.attempts + 1
             WHERE s.step_id = (SELECT st.step_id
                                  FROM learning_hub.course_drafting_steps st
                                  JOIN learning_hub.course_drafting_sessions se ON se.session_id = st.session_id
                                 WHERE st.state = 'proposing' AND st.claimed_at IS NULL AND NOT st.stopped
                                   AND se.status = 'active'
                                 ORDER BY st.update_time, st.step_id
                                 FOR UPDATE OF st SKIP LOCKED
                                 LIMIT 1)
            RETURNING """ + " " + STEP_COLUMNS, STEP).stream().findFirst();
    }

    /** The claimed step holds its proposal; false when it is no longer being proposed (stopped meanwhile). */
    public boolean storeProposal(long stepId, String proposalJson, String notesJson) {
        return jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET state = 'proposed', proposal = ?::jsonb, notes = ?::jsonb, error_message = NULL, claimed_at = NULL,
                   update_time = now()
             WHERE step_id = ? AND state = 'proposing'
            """, proposalJson, notesJson, stepId) == 1;
    }

    public void failStep(long stepId, String errorMessage) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET state = 'failed', error_message = ?, claimed_at = NULL, update_time = now()
             WHERE step_id = ? AND state = 'proposing'
            """, errorMessage, stepId);
    }

    /** A step whose model call timed out waits again; the attempt it used still counts. */
    public void retryStep(long stepId) {
        jdbc.update("UPDATE learning_hub.course_drafting_steps SET claimed_at = NULL WHERE step_id = ? "
            + "AND state = 'proposing'", stepId);
    }

    /** A step the source or the model was not ready for waits again without costing an attempt. */
    public void releaseStep(long stepId) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps SET claimed_at = NULL, attempts = GREATEST(attempts - 1, 0)
             WHERE step_id = ? AND state = 'proposing'
            """, stepId);
    }

    /** Steps claimed when the server stopped that have already used {@code maxAttempts} attempts. */
    public List<DraftingStepRow> interruptedAtCap(int maxAttempts) {
        return jdbc.query("SELECT " + STEP_COLUMNS + " FROM learning_hub.course_drafting_steps "
            + "WHERE state = 'proposing' AND claimed_at IS NOT NULL AND attempts >= ?", STEP, maxAttempts);
    }

    /** A step claimed when the server stopped waits again. */
    public int requeueInterrupted() {
        return jdbc.update("UPDATE learning_hub.course_drafting_steps SET claimed_at = NULL "
            + "WHERE state = 'proposing' AND claimed_at IS NOT NULL");
    }

    // ---- what a proposal is checked against ------------------------------------------------------------------------

    /** Whether a course already has the code, ignoring case, as the course service checks it. */
    public boolean codeTaken(String code) {
        Boolean taken = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM learning_hub.courses WHERE lower(code) = lower(?))
            """, Boolean.class, code);

        return Boolean.TRUE.equals(taken);
    }

    /**
     * Programmes a course of the unit may belong to: the unit owns the programme and its field (what the course service
     * accepts in a draft), archived ones left out. At most {@code limit}, by sort order.
     */
    public List<Candidate> programmes(long ownerOrgId, int limit) {
        return jdbc.query("""
            SELECT p.program_id, p.name, p.field_id
              FROM learning_hub.training_programs p
              JOIN learning_hub.training_fields f ON f.field_id = p.field_id
             WHERE p.owner_org_id = ? AND f.owner_org_id = ?
               AND p.status <> 'archived' AND f.status <> 'archived'
             ORDER BY p.sort_no, p.program_id
             LIMIT ?
            """, (rs, row) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getLong(3)), ownerOrgId, ownerOrgId,
            limit);
    }

    /** Business modules of the unit's fields, archived ones left out. At most {@code limit}, by sort order. */
    public List<Candidate> modules(long ownerOrgId, int limit) {
        return jdbc.query("""
            SELECT m.module_id, m.name, m.field_id
              FROM learning_hub.business_modules m
              JOIN learning_hub.training_fields f ON f.field_id = m.field_id
             WHERE f.owner_org_id = ?
               AND m.status <> 'archived' AND f.status <> 'archived'
             ORDER BY m.sort_no, m.module_id
             LIMIT ?
            """, (rs, row) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getLong(3)), ownerOrgId, limit);
    }

    public boolean conversationOwnedBy(long conversationId, String userId) {
        Boolean owned = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM learning_hub.assistant_conversations
                            WHERE conversation_id = ? AND owner_id = ?)
            """, Boolean.class, conversationId, userId);

        return Boolean.TRUE.equals(owned);
    }

    public Optional<DocumentInfo> document(long documentId) {
        return jdbc.query("""
            SELECT document_id, file_name, status FROM learning_hub.knowledge_documents WHERE document_id = ?
            """, (rs, row) -> new DocumentInfo(rs.getLong(1), rs.getString(2), rs.getString(3)), documentId)
            .stream().findFirst();
    }
}
