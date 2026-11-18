package vn.com.coreacademy.assistant.drafting;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.knowledge.entity.DocumentScope;

/**
 * The drafting sessions and their steps. A step in {@code proposing} with no {@code claimed_at} waits for the worker;
 * every transition is one statement guarded by the state it expects, so a stray call cannot overwrite a later state.
 * States are bound from {@link DraftingStates}, never written into the SQL.
 */
@Repository
@RequiredArgsConstructor
public class CourseDraftingRepository {
    /** {@code proposer} is who makes the Đề xuất ({@link DraftingStates#IN_HOUSE} or {@link DraftingStates#EXTERNAL}). */
    public record SessionRow(long id, String ownerUserId, long ownerOrgId, Long sourceDocumentId, Long conversationId,
                             Long courseId, Long revisionId, int currentStep, String status, String errorMessage,
                             String hints, String sourceSummary, OffsetDateTime createTime,
                             OffsetDateTime updateTime, String proposer, String sourceName) {
        /** A session of the in-house assistant. */
        public SessionRow(long id, String ownerUserId, long ownerOrgId, Long sourceDocumentId, Long conversationId,
                          Long courseId, Long revisionId, int currentStep, String status, String errorMessage,
                          String hints, String sourceSummary, OffsetDateTime createTime, OffsetDateTime updateTime) {
            this(id, ownerUserId, ownerOrgId, sourceDocumentId, conversationId, courseId, revisionId, currentStep,
                status, errorMessage, hints, sourceSummary, createTime, updateTime, DraftingStates.IN_HOUSE, null);
        }

        /** A Trợ lý ngoài makes this session's Đề xuất: the worker and the chat leave it alone. */
        public boolean external() {
            return DraftingStates.EXTERNAL.equals(proposer);
        }
    }

    public record DraftingStepRow(long id, long sessionId, int stepNo, String state, String proposal, String notes,
                                  String errorMessage, int attempts, OffsetDateTime claimedAt, boolean stopped,
                                  OffsetDateTime updateTime) {
    }

    /** A programme or module the author may pick, with the field it belongs to. */
    public record Candidate(long id, String name, long fieldId) {
    }

    /** A competency the author may pick for the course's questions. */
    public record Choice(long id, String name) {
    }

    /** The course as step 1 saved it, what the outline is built for. */
    public record CourseBrief(String title, String summary, String level, List<String> objectives) {
    }

    public record DocumentInfo(long id, String fileName, String status) {
    }

    /** A step's state as a chat card shows it, with its session's draft course once step 1 created it. */
    public record StepState(long sessionId, int stepNo, String state, Long courseId) {
    }

    /** A unit or team of the course's owner unit: {@code org} or {@code team}, with the unit a team belongs to. */
    public record Unit(String targetType, long id, String name, long orgId) {
    }

    /** The saved draft as the pre-publish check reads it; {@code finalQuizId} is the selected final exam, if any. */
    public record DraftSnapshot(String title, Long programId, String level, boolean hasObjective,
                                List<LessonSnapshot> lessons, Long finalQuizId) {
    }

    /** A lesson and its blocks, in outline order. */
    public record LessonSnapshot(String lessonType, Long quizId, Long scormPackageId, List<BlockSnapshot> blocks) {
    }

    /** A block with what makes it complete: its text, its process steps and its files. */
    public record BlockSnapshot(String blockType, String body, int steps, int assets) {
    }

    private static final RowMapper<SessionRow> SESSION = (rs, row) -> new SessionRow(rs.getLong("session_id"),
        rs.getString("owner_user_id"), rs.getLong("owner_org_id"), (Long) rs.getObject("source_document_id"),
        (Long) rs.getObject("conversation_id"), (Long) rs.getObject("course_id"), (Long) rs.getObject("revision_id"),
        rs.getInt("current_step"), rs.getString("status"), rs.getString("error_message"), rs.getString("hints"),
        rs.getString("source_summary"), rs.getObject("create_time", OffsetDateTime.class),
        rs.getObject("update_time", OffsetDateTime.class), rs.getString("proposer"), rs.getString("source_name"));
    private static final RowMapper<DraftingStepRow> STEP = (rs, row) -> new DraftingStepRow(rs.getLong("step_id"),
        rs.getLong("session_id"), rs.getInt("step_no"), rs.getString("state"), rs.getString("proposal"),
        rs.getString("notes"), rs.getString("error_message"), rs.getInt("attempts"),
        rs.getObject("claimed_at", OffsetDateTime.class), rs.getBoolean("stopped"),
        rs.getObject("update_time", OffsetDateTime.class));
    private static final String COURSE_SOURCE = DocumentScope.COURSE_SOURCE.getValue();

    private final NamedParameterJdbcTemplate jdbc;

    /** The parameters every guarded statement may name: the drafting states, bound instead of written as literals. */
    private static MapSqlParameterSource states() {
        return new MapSqlParameterSource()
            .addValue("proposing", DraftingStates.PROPOSING)
            .addValue("proposed", DraftingStates.PROPOSED)
            .addValue("confirmed", DraftingStates.CONFIRMED)
            .addValue("failedStep", DraftingStates.FAILED)
            .addValue("manualStep", DraftingStates.MANUAL)
            .addValue("active", DraftingStates.ACTIVE)
            .addValue("finished", DraftingStates.FINISHED)
            .addValue("manualSession", DraftingStates.MANUAL_SESSION)
            .addValue("failedSession", DraftingStates.FAILED_SESSION);
    }

    // ---- sessions --------------------------------------------------------------------------------------------------

    public long insertSession(String ownerUserId, long ownerOrgId, long sourceDocumentId, Long conversationId,
                              String hintsJson) {
        return insertSession(ownerUserId, ownerOrgId, sourceDocumentId, conversationId, hintsJson,
            DraftingStates.IN_HOUSE, null);
    }

    /** A session of a Trợ lý ngoài: no source document (the server never receives it), only its name for display. */
    public long insertExternalSession(String ownerUserId, long ownerOrgId, String sourceName) {
        return insertSession(ownerUserId, ownerOrgId, null, null, "{}", DraftingStates.EXTERNAL, sourceName);
    }

    private long insertSession(String ownerUserId, long ownerOrgId, Long sourceDocumentId, Long conversationId,
                               String hintsJson, String proposer, String sourceName) {
        return jdbc.queryForObject("""
            INSERT INTO learning_hub.course_drafting_sessions (owner_user_id, owner_org_id, source_document_id,
                                                               conversation_id, current_step, status, hints,
                                                               proposer, source_name)
            VALUES (:owner, :org, :document, :conversation, :firstStep, :active, :hints::jsonb, :proposer,
                    :sourceName)
            RETURNING session_id
            """, states().addValue("owner", ownerUserId).addValue("org", ownerOrgId)
            .addValue("document", sourceDocumentId).addValue("conversation", conversationId)
            .addValue("firstStep", DraftingStates.FIRST_STEP).addValue("hints", hintsJson)
            .addValue("proposer", proposer).addValue("sourceName", sourceName), Long.class);
    }

    public Optional<SessionRow> session(long sessionId) {
        return jdbc.query("""
            SELECT session_id, owner_user_id, owner_org_id, source_document_id, conversation_id, course_id,
                   revision_id, current_step, status, error_message, hints::text AS hints,
                   source_summary::text AS source_summary, create_time, update_time, proposer, source_name
              FROM learning_hub.course_drafting_sessions
             WHERE session_id = :session
            """, Map.of("session", sessionId), SESSION).stream().findFirst();
    }

    /**
     * Start times of the user's course generation since the instant, oldest first: old jobs (deleted ones too) and
     * drafting sessions count against the same daily allowance. Sessions a Trợ lý ngoài drives do not: they cost the
     * in-house model nothing (they have their own limit of open sessions).
     */
    public List<OffsetDateTime> startedSince(String ownerUserId, OffsetDateTime since) {
        return jdbc.queryForList("""
            SELECT started
              FROM (SELECT create_time AS started
                      FROM learning_hub.course_generation_jobs
                     WHERE owner_user_id = :owner AND create_time > :since
                    UNION ALL
                    SELECT create_time
                      FROM learning_hub.course_drafting_sessions
                     WHERE owner_user_id = :owner AND create_time > :since AND proposer = :inHouse
                    UNION ALL
                    SELECT create_time
                      FROM learning_hub.course_drafting_reproposals
                     WHERE owner_user_id = :owner AND create_time > :since) starts
             ORDER BY started
            """, Map.of("owner", ownerUserId, "since", since, "inHouse", DraftingStates.IN_HOUSE), OffsetDateTime.class);
    }

    /**
     * Upload times, oldest first, of the user's course sources uploaded since the instant that no session or old job
     * uses yet: the chat attachments the daily upload cap counts. A source a session was started from is covered by
     * the start allowance instead.
     */
    public List<OffsetDateTime> unusedSourcesSince(String userId, OffsetDateTime since) {
        return jdbc.queryForList("""
            SELECT d.create_time
              FROM learning_hub.knowledge_documents d
             WHERE d.uploaded_by = :user AND d.scope = :scope AND d.create_time > :since
               AND NOT EXISTS (SELECT 1 FROM learning_hub.course_drafting_sessions s
                                WHERE s.source_document_id = d.document_id)
               AND NOT EXISTS (SELECT 1 FROM learning_hub.course_generation_jobs j
                                WHERE j.source_document_id = d.document_id)
             ORDER BY d.create_time
            """, Map.of("user", userId, "scope", COURSE_SOURCE, "since", since), OffsetDateTime.class);
    }

    /**
     * Course sources uploaded before the instant that no drafting session and no old job uses: chat attachments
     * nobody started a session from. Oldest first, at most {@code limit}.
     */
    public List<Long> unusedSourcesBefore(OffsetDateTime before, int limit) {
        return jdbc.queryForList("""
            SELECT d.document_id
              FROM learning_hub.knowledge_documents d
             WHERE d.scope = :scope AND d.create_time < :before
               AND NOT EXISTS (SELECT 1 FROM learning_hub.course_drafting_sessions s
                                WHERE s.source_document_id = d.document_id)
               AND NOT EXISTS (SELECT 1 FROM learning_hub.course_generation_jobs j
                                WHERE j.source_document_id = d.document_id)
             ORDER BY d.create_time, d.document_id
             LIMIT :limit
            """, Map.of("scope", COURSE_SOURCE, "before", before, "limit", limit), Long.class);
    }

    /** "Đề xuất lại" or "Thử lại" on a step: it counts against the daily allowance like a start. */
    public void recordReproposal(long sessionId, int stepNo, String ownerUserId) {
        jdbc.update("""
            INSERT INTO learning_hub.course_drafting_reproposals (session_id, step_no, owner_user_id)
            VALUES (:session, :step, :owner)
            """, Map.of("session", sessionId, "step", stepNo, "owner", ownerUserId));
    }

    /** Stores the summary of the source once; every later step and proposal reads it instead of the document. */
    public void setSourceSummary(long sessionId, String summaryJson) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions
               SET source_summary = :summary::jsonb, update_time = now()
             WHERE session_id = :session
            """, Map.of("summary", summaryJson, "session", sessionId));
    }

    /** Ends an active session as failed with a message the chat and the page can show. */
    public void failSession(long sessionId, String errorMessage) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions
               SET status = :failedSession, error_message = :error, update_time = now()
             WHERE session_id = :session AND status = :active
            """, states().addValue("error", errorMessage).addValue("session", sessionId));
    }

    /** The session, locked until the transaction ends, so two confirms of one step run one after the other. */
    public Optional<SessionRow> lockSession(long sessionId) {
        return jdbc.query("""
            SELECT session_id, owner_user_id, owner_org_id, source_document_id, conversation_id, course_id,
                   revision_id, current_step, status, error_message, hints::text AS hints,
                   source_summary::text AS source_summary, create_time, update_time, proposer, source_name
              FROM learning_hub.course_drafting_sessions
             WHERE session_id = :session
               FOR UPDATE
            """, Map.of("session", sessionId), SESSION).stream().findFirst();
    }

    /** The author's latest session of the course, the one its edit page follows. */
    public Optional<SessionRow> latestForCourse(long courseId, String ownerUserId) {
        return jdbc.query("""
            SELECT session_id, owner_user_id, owner_org_id, source_document_id, conversation_id, course_id,
                   revision_id, current_step, status, error_message, hints::text AS hints,
                   source_summary::text AS source_summary, create_time, update_time, proposer, source_name
              FROM learning_hub.course_drafting_sessions
             WHERE course_id = :course AND owner_user_id = :owner
             ORDER BY create_time DESC, session_id DESC
             LIMIT 1
            """, Map.of("course", courseId, "owner", ownerUserId), SESSION).stream().findFirst();
    }

    /** Step 1 is confirmed: the session holds the draft course it created and moves on to the step. */
    public void linkCourse(long sessionId, long courseId, long revisionId, int nextStep) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions
               SET course_id = :course, revision_id = :revision, current_step = :next, update_time = now()
             WHERE session_id = :session
            """, Map.of("course", courseId, "revision", revisionId, "next", nextStep, "session", sessionId));
    }

    public void confirmStep(long sessionId, int stepNo) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET state = :confirmed, claimed_at = NULL, error_message = NULL, update_time = now()
             WHERE session_id = :session AND step_no = :step
            """, states().addValue("session", sessionId).addValue("step", stepNo));
    }

    /**
     * Step 2 is confirmed; a proposed outline keeps the saved lessons' notes ({@code lessonNotes}) beside its chapters.
     * A step with no proposal (failed, manual) keeps none.
     */
    public void confirmOutline(long sessionId, int stepNo, String lessonNotesJson) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET state = :confirmed, claimed_at = NULL, error_message = NULL, update_time = now(),
                   proposal = CASE WHEN proposal IS NULL THEN NULL
                                   ELSE jsonb_set(proposal, '{lessonNotes}', :notes::jsonb) END
             WHERE session_id = :session AND step_no = :step
            """, states().addValue("notes", lessonNotesJson).addValue("session", sessionId).addValue("step", stepNo));
    }

    /** A failed session is active again, its error cleared, because the author asked for its step once more. */
    public void reactivate(long sessionId) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions
               SET status = :active, error_message = NULL, update_time = now()
             WHERE session_id = :session AND status IN (:active, :failedSession)
            """, states().addValue("session", sessionId));
    }

    /** "Tự điền": the assistant's part of the session is over; the author fills the rest of the course by hand. */
    public void endAsManual(long sessionId) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions
               SET status = :manualSession, error_message = NULL, update_time = now()
             WHERE session_id = :session AND status IN (:active, :failedSession)
            """, states().addValue("session", sessionId));
    }

    /** The session moves on to the step. */
    public void advance(long sessionId, int nextStep) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions
               SET current_step = :next, update_time = now()
             WHERE session_id = :session
            """, Map.of("next", nextStep, "session", sessionId));
    }

    /** The draft holds content the assistant proposed, so publishing asks the author to confirm the review. */
    public void markAiGenerated(long revisionId) {
        jdbc.update("""
            UPDATE learning_hub.course_revisions
               SET ai_generated = true
             WHERE revision_id = :revision
            """, Map.of("revision", revisionId));
    }

    /**
     * The step proposes another course code after the one confirmed was taken, with a note for the chat. A step with
     * no proposal yet (failed, filled by hand) gets one holding only the code, so the page still offers it.
     */
    public void proposeCode(long sessionId, int stepNo, String code, String note) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET proposal = jsonb_set(COALESCE(proposal, '{}'::jsonb), '{code}', to_jsonb(:code::text)),
                   notes = notes || jsonb_build_array(:note::text), update_time = now()
             WHERE session_id = :session AND step_no = :step
            """, Map.of("code", code, "note", note, "session", sessionId, "step", stepNo));
    }

    public void touchSession(long sessionId) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions
               SET update_time = now()
             WHERE session_id = :session
            """, Map.of("session", sessionId));
    }

    /** The last step is confirmed: the assistant's part of the session is over. */
    public void finish(long sessionId) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions
               SET status = :finished, update_time = now()
             WHERE session_id = :session AND status = :active
            """, states().addValue("session", sessionId));
    }

    // ---- steps -----------------------------------------------------------------------------------------------------

    /** The step starts being proposed; the worker picks it up. */
    public void insertStep(long sessionId, int stepNo) {
        jdbc.update("""
            INSERT INTO learning_hub.course_drafting_steps (session_id, step_no, state)
            VALUES (:session, :step, :proposing)
            """, states().addValue("session", sessionId).addValue("step", stepNo));
    }

    /**
     * "Đề xuất lại" or "Thử lại": the step waits for the worker again, its last proposal and error gone. Only a step
     * that holds a proposal or failed; false for one being proposed, filled by hand or confirmed. Its attempts keep
     * counting, so a step is not proposed again without end.
     */
    public boolean reopenStep(long sessionId, int stepNo) {
        return jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET state = :proposing, proposal = NULL, error_message = NULL, claimed_at = NULL, stopped = false,
                   update_time = now()
             WHERE session_id = :session AND step_no = :step AND state IN (:proposed, :failedStep)
            """, states().addValue("session", sessionId).addValue("step", stepNo)) == 1;
    }

    /**
     * "Dừng": the steps being proposed stop. One that still holds a proposal keeps it; one with nothing yet is the
     * author's to fill. A worker still writing it finds the step changed and drops what it brings.
     */
    public void stopProposing(long sessionId) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET state = CASE WHEN proposal IS NULL THEN :manualStep ELSE :proposed END, stopped = true,
                   claimed_at = NULL, update_time = now()
             WHERE session_id = :session AND state = :proposing
            """, states().addValue("session", sessionId));
    }

    /** "Tự điền": the author fills the step by hand; nothing the assistant still brings for it is stored. */
    public boolean manualStep(long sessionId, int stepNo) {
        return jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET stopped = (state = :proposing), state = :manualStep, error_message = NULL, claimed_at = NULL,
                   update_time = now()
             WHERE session_id = :session AND step_no = :step AND state <> :confirmed
            """, states().addValue("session", sessionId).addValue("step", stepNo)) == 1;
    }

    /** The steps reached, ascending. */
    public List<DraftingStepRow> steps(long sessionId) {
        return jdbc.query("""
            SELECT step_id, session_id, step_no, state, proposal::text AS proposal, notes::text AS notes,
                   error_message, attempts, claimed_at, stopped, update_time
              FROM learning_hub.course_drafting_steps
             WHERE session_id = :session
             ORDER BY step_no
            """, Map.of("session", sessionId), STEP);
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
                                 WHERE st.state = :proposing AND st.claimed_at IS NULL AND NOT st.stopped
                                   AND se.status = :active
                                 ORDER BY st.update_time, st.step_id
                                 FOR UPDATE OF st SKIP LOCKED
                                 LIMIT 1)
            RETURNING step_id, session_id, step_no, state, proposal::text AS proposal, notes::text AS notes,
                      error_message, attempts, claimed_at, stopped, update_time
            """, states(), STEP).stream().findFirst();
    }

    // A worker's writes name the claim they finish ({@code claimedAt}): once the author stopped the step, filled it by
    // hand or asked for it again, that claim no longer owns the step and its late result changes nothing.

    /** The claimed step holds its proposal; false when the claim no longer owns the step. */
    public boolean storeProposal(long stepId, OffsetDateTime claimedAt, String proposalJson, String notesJson) {
        return jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET state = :proposed, proposal = :proposal::jsonb, notes = :notes::jsonb, error_message = NULL,
                   claimed_at = NULL, update_time = now()
             WHERE step_id = :stepId AND state = :proposing AND claimed_at = :claimedAt
            """, states().addValue("proposal", proposalJson).addValue("notes", notesJson).addValue("stepId", stepId)
            .addValue("claimedAt", claimedAt)) == 1;
    }

    /** The claimed step failed; false when the claim no longer owns the step. */
    public boolean failStep(long stepId, OffsetDateTime claimedAt, String errorMessage) {
        return jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET state = :failedStep, error_message = :error, claimed_at = NULL, update_time = now()
             WHERE step_id = :stepId AND state = :proposing AND claimed_at = :claimedAt
            """, states().addValue("error", errorMessage).addValue("stepId", stepId)
            .addValue("claimedAt", claimedAt)) == 1;
    }

    /** A step whose model call timed out waits again; the attempt it used still counts. */
    public void retryStep(long stepId, OffsetDateTime claimedAt) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET claimed_at = NULL
             WHERE step_id = :stepId AND state = :proposing AND claimed_at = :claimedAt
            """, states().addValue("stepId", stepId).addValue("claimedAt", claimedAt));
    }

    /** A step the source or the model was not ready for waits again without costing an attempt. */
    public void releaseStep(long stepId, OffsetDateTime claimedAt) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET claimed_at = NULL, attempts = GREATEST(attempts - 1, 0)
             WHERE step_id = :stepId AND state = :proposing AND claimed_at = :claimedAt
            """, states().addValue("stepId", stepId).addValue("claimedAt", claimedAt));
    }

    /**
     * Steps claimed before {@code claimedBefore} (so no live worker still owns them), other than {@code runningStepId},
     * that have already used {@code maxAttempts} attempts.
     */
    public List<DraftingStepRow> interruptedAtCap(int maxAttempts, OffsetDateTime claimedBefore, long runningStepId) {
        return jdbc.query("""
            SELECT step_id, session_id, step_no, state, proposal::text AS proposal, notes::text AS notes,
                   error_message, attempts, claimed_at, stopped, update_time
              FROM learning_hub.course_drafting_steps
             WHERE state = :proposing AND claimed_at < :claimedBefore AND attempts >= :maxAttempts
               AND step_id <> :running
            """, states().addValue("claimedBefore", claimedBefore).addValue("maxAttempts", maxAttempts)
            .addValue("running", runningStepId), STEP);
    }

    /**
     * A step claimed before {@code claimedBefore} waits again: its worker stopped with the server. A newer claim may
     * belong to a worker of another instance that is still calling the model, so it is left alone, and so is
     * {@code runningStepId}, the step this instance is proposing.
     */
    public int requeueInterrupted(OffsetDateTime claimedBefore, long runningStepId) {
        return jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET claimed_at = NULL
             WHERE state = :proposing AND claimed_at < :claimedBefore AND step_id <> :running
            """, states().addValue("claimedBefore", claimedBefore).addValue("running", runningStepId));
    }

    // ---- what a proposal is checked against ------------------------------------------------------------------------

    /** Whether a course already has the code, ignoring case, as the course service checks it. */
    public boolean codeTaken(String code) {
        Boolean taken = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM learning_hub.courses WHERE lower(code) = lower(:code))
            """, Map.of("code", code), Boolean.class);

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
             WHERE p.owner_org_id = :org AND f.owner_org_id = :org
               AND p.status <> 'archived' AND f.status <> 'archived'
             ORDER BY p.sort_no, p.program_id
             LIMIT :limit
            """, Map.of("org", ownerOrgId, "limit", limit),
            (rs, row) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getLong(3)));
    }

    /** Business modules of the unit's fields, archived ones left out. At most {@code limit}, by sort order. */
    public List<Candidate> modules(long ownerOrgId, int limit) {
        return jdbc.query("""
            SELECT m.module_id, m.name, m.field_id
              FROM learning_hub.business_modules m
              JOIN learning_hub.training_fields f ON f.field_id = m.field_id
             WHERE f.owner_org_id = :org
               AND m.status <> 'archived' AND f.status <> 'archived'
             ORDER BY m.sort_no, m.module_id
             LIMIT :limit
            """, Map.of("org", ownerOrgId, "limit", limit),
            (rs, row) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getLong(3)));
    }

    /**
     * Competencies a new question may be tagged with: active and not deleted (what the question bank accepts). They are
     * shared master data, so every author picks from the same list. At most {@code limit}, by sort order.
     */
    public List<Choice> competencies(int limit) {
        return jdbc.query("""
            SELECT competency_id, name
              FROM learning_hub.competencies
             WHERE NOT is_deleted AND status = 'ACTIVE'
             ORDER BY sort_no, competency_id
             LIMIT :limit
            """, Map.of("limit", limit), (rs, row) -> new Choice(rs.getLong(1), rs.getString(2)));
    }

    /** The draft revision's title, summary, level and objectives; empty when the revision is gone. */
    public Optional<CourseBrief> courseBrief(long revisionId) {
        Map<String, Long> revision = Map.of("revision", revisionId);
        List<String> objectives = jdbc.queryForList("""
            SELECT description
              FROM learning_hub.course_objectives
             WHERE revision_id = :revision
             ORDER BY sort_no
            """, revision, String.class);
        return jdbc.query("""
            SELECT title, summary, level
              FROM learning_hub.course_revisions
             WHERE revision_id = :revision
            """, revision, (rs, row) -> new CourseBrief(rs.getString(1), rs.getString(2), rs.getString(3),
            objectives)).stream().findFirst();
    }

    public boolean conversationOwnedBy(long conversationId, String userId) {
        Boolean owned = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM learning_hub.assistant_conversations
                            WHERE conversation_id = :conversation AND owner_id = :user)
            """, Map.of("conversation", conversationId, "user", userId), Boolean.class);

        return Boolean.TRUE.equals(owned);
    }

    /**
     * The document is the user's own course source and no session or old job uses it yet: what the chat may hand to a
     * new session. Any other id (someone else's, a shared document, one already used) is not.
     */
    public boolean freeSourceOf(long documentId, String userId) {
        Boolean free = jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM learning_hub.knowledge_documents d
                            WHERE d.document_id = :document AND d.uploaded_by = :user AND d.scope = :scope
                              AND NOT EXISTS (SELECT 1 FROM learning_hub.course_drafting_sessions s
                                               WHERE s.source_document_id = d.document_id)
                              AND NOT EXISTS (SELECT 1 FROM learning_hub.course_generation_jobs j
                                               WHERE j.source_document_id = d.document_id))
            """, Map.of("document", documentId, "user", userId, "scope", COURSE_SOURCE), Boolean.class);

        return Boolean.TRUE.equals(free);
    }

    /** The state of each step of these sessions of the owner, with the session's draft course: the chat's cards. */
    public List<StepState> stepStates(String ownerUserId, List<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
            SELECT s.session_id, st.step_no, st.state, s.course_id
              FROM learning_hub.course_drafting_sessions s
              JOIN learning_hub.course_drafting_steps st ON st.session_id = s.session_id
             WHERE s.owner_user_id = :owner AND s.session_id IN (:sessions)
            """, Map.of("owner", ownerUserId, "sessions", sessionIds),
            (rs, row) -> new StepState(rs.getLong(1), rs.getInt(2), rs.getString(3), (Long) rs.getObject(4)));
    }

    public Optional<DocumentInfo> document(long documentId) {
        return jdbc.query("""
            SELECT document_id, file_name, status
              FROM learning_hub.knowledge_documents
             WHERE document_id = :document
            """, Map.of("document", documentId),
            (rs, row) -> new DocumentInfo(rs.getLong(1), rs.getString(2), rs.getString(3))).stream().findFirst();
    }

    /**
     * The active units of the subtree under {@code ownerOrgId} (the unit itself first) and their active teams, what a
     * course of that unit may be assigned to before the author's own scope is checked. At most {@code limit} of each.
     */
    public List<Unit> units(long ownerOrgId, int limit) {
        List<Unit> units = new ArrayList<>(jdbc.query("""
            WITH RECURSIVE subtree(org_id, depth) AS (
                SELECT org_id, 0 FROM learning_hub.orgs WHERE org_id = :org AND status = 'active'
                UNION
                SELECT o.org_id, s.depth + 1 FROM learning_hub.orgs o JOIN subtree s ON o.parent_id = s.org_id
                 WHERE o.status = 'active'
            )
            SELECT o.org_id, o.name FROM subtree s JOIN learning_hub.orgs o ON o.org_id = s.org_id
             ORDER BY s.depth, o.sort_no, o.name, o.org_id
             LIMIT :limit
            """, Map.of("org", ownerOrgId, "limit", limit),
            (rs, row) -> new Unit("org", rs.getLong(1), rs.getString(2), rs.getLong(1))));
        List<Long> orgIds = units.stream().map(Unit::id).toList();
        if (orgIds.isEmpty()) {
            return units;
        }

        units.addAll(jdbc.query("""
            SELECT team_id, name, org_id
              FROM learning_hub.teams
             WHERE status = 'active' AND org_id IN (:orgs)
             ORDER BY name, team_id
             LIMIT :limit
            """, Map.of("orgs", orgIds, "limit", limit),
            (rs, row) -> new Unit("team", rs.getLong(1), rs.getString(2), rs.getLong(3))));

        return units;
    }

    /** The draft revision as the pre-publish check reads it; empty when the revision is gone. */
    public Optional<DraftSnapshot> draftSnapshot(long revisionId) {
        Map<String, Long> revision = Map.of("revision", revisionId);
        boolean objective = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM learning_hub.course_objectives
                            WHERE revision_id = :revision AND btrim(description) <> '')
            """, revision, Boolean.class));
        Map<Long, List<BlockSnapshot>> blocks = new HashMap<>();
        jdbc.query("""
            SELECT b.lesson_id, b.block_type, b.body,
                   (SELECT count(*) FROM learning_hub.process_steps s WHERE s.block_id = b.block_id),
                   (SELECT count(*) FROM learning_hub.block_assets a WHERE a.block_id = b.block_id)
              FROM learning_hub.lesson_blocks b
              JOIN learning_hub.course_lessons l ON l.lesson_id = b.lesson_id
              JOIN learning_hub.course_chapters c ON c.chapter_id = l.chapter_id
             WHERE c.revision_id = :revision
             ORDER BY b.sort_no, b.block_id
            """, revision, (RowCallbackHandler) rs -> blocks
            .computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
            .add(new BlockSnapshot(rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5))));
        List<LessonSnapshot> lessons = jdbc.query("""
            SELECT l.lesson_id, l.lesson_type, l.quiz_id, l.scorm_package_id
              FROM learning_hub.course_lessons l
              JOIN learning_hub.course_chapters c ON c.chapter_id = l.chapter_id
             WHERE c.revision_id = :revision
             ORDER BY c.sort_no, l.sort_no, l.lesson_id
            """, revision, (rs, row) -> new LessonSnapshot(rs.getString(2), rs.getObject(3, Long.class),
            rs.getObject(4, Long.class), blocks.getOrDefault(rs.getLong(1), List.of())));
        // The final exam as the course detail reads it: the exam link overrides the completion rule's legacy column.
        Long finalQuizId = jdbc.query("""
            SELECT COALESCE(
                (SELECT assessment_revision_id FROM learning_hub.course_assessments
                  WHERE revision_id = :revision AND placement = 'final' ORDER BY link_id DESC LIMIT 1),
                (SELECT final_quiz_id FROM learning_hub.course_completion_rules WHERE revision_id = :revision LIMIT 1))
            """, revision, (rs, row) -> rs.getObject(1, Long.class)).stream().findFirst().orElse(null);

        return jdbc.query("""
            SELECT title, program_id, level
              FROM learning_hub.course_revisions
             WHERE revision_id = :revision
            """, revision, (rs, row) -> new DraftSnapshot(rs.getString(1), rs.getObject(2, Long.class),
            rs.getString(3), objective, lessons, finalQuizId)).stream().findFirst();
    }
}
