package vn.com.coreacademy.assistant.drafting;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
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

    /** A competency the author may pick for the course's questions. */
    public record Choice(long id, String name) {
    }

    /** The course as step 1 saved it, what the outline is built for. */
    public record CourseBrief(String title, String summary, String level, List<String> objectives) {
    }

    public record DocumentInfo(long id, String fileName, String status) {
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

    /** The session, locked until the transaction ends, so two confirms of one step run one after the other. */
    public Optional<SessionRow> lockSession(long sessionId) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM learning_hub.course_drafting_sessions "
            + "WHERE session_id = ? FOR UPDATE", SESSION, sessionId).stream().findFirst();
    }

    /** The author's latest session of the course, the one its edit page follows. */
    public Optional<SessionRow> latestForCourse(long courseId, String ownerUserId) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM learning_hub.course_drafting_sessions "
            + "WHERE course_id = ? AND owner_user_id = ? ORDER BY create_time DESC, session_id DESC LIMIT 1", SESSION,
            courseId, ownerUserId).stream().findFirst();
    }

    /** Step 1 is confirmed: the session holds the draft course it created and moves on to the step. */
    public void linkCourse(long sessionId, long courseId, long revisionId, int nextStep) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions
               SET course_id = ?, revision_id = ?, current_step = ?, update_time = now()
             WHERE session_id = ?
            """, courseId, revisionId, nextStep, sessionId);
    }

    public void confirmStep(long sessionId, int stepNo) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET state = 'confirmed', claimed_at = NULL, error_message = NULL, update_time = now()
             WHERE session_id = ? AND step_no = ?
            """, sessionId, stepNo);
    }

    /**
     * Step 2 is confirmed; a proposed outline keeps the saved lessons' notes ({@code lessonNotes}) beside its chapters.
     * A step with no proposal (failed, manual) keeps none.
     */
    public void confirmOutline(long sessionId, int stepNo, String lessonNotesJson) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET state = 'confirmed', claimed_at = NULL, error_message = NULL, update_time = now(),
                   proposal = CASE WHEN proposal IS NULL THEN NULL
                                   ELSE jsonb_set(proposal, '{lessonNotes}', ?::jsonb) END
             WHERE session_id = ? AND step_no = ?
            """, lessonNotesJson, sessionId, stepNo);
    }

    /** The session moves on to the step. */
    public void advance(long sessionId, int nextStep) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions SET current_step = ?, update_time = now()
             WHERE session_id = ?
            """, nextStep, sessionId);
    }

    /** The draft holds content the assistant proposed, so publishing asks the author to confirm the review. */
    public void markAiGenerated(long revisionId) {
        jdbc.update("UPDATE learning_hub.course_revisions SET ai_generated = true WHERE revision_id = ?", revisionId);
    }

    /**
     * The step proposes another course code after the one confirmed was taken, with a note for the chat. A step with
     * no proposal (failed, manual) keeps none: the code field is the author's then.
     */
    public void proposeCode(long sessionId, int stepNo, String code, String note) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_steps
               SET proposal = jsonb_set(proposal, '{code}', to_jsonb(?::text)),
                   notes = notes || jsonb_build_array(?::text), update_time = now()
             WHERE session_id = ? AND step_no = ? AND proposal IS NOT NULL
            """, code, note, sessionId, stepNo);
    }

    public void touchSession(long sessionId) {
        jdbc.update("UPDATE learning_hub.course_drafting_sessions SET update_time = now() WHERE session_id = ?",
            sessionId);
    }

    // ---- steps -----------------------------------------------------------------------------------------------------

    /** The last step is confirmed: the assistant's part of the session is over. */
    public void finish(long sessionId) {
        jdbc.update("""
            UPDATE learning_hub.course_drafting_sessions SET status = 'finished', update_time = now()
             WHERE session_id = ? AND status = 'active'
            """, sessionId);
    }

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

    /**
     * Competencies a new question may be tagged with: active and not deleted (what the question bank accepts). They are
     * shared master data, so every author picks from the same list. At most {@code limit}, by sort order.
     */
    public List<Choice> competencies(int limit) {
        return jdbc.query("""
            SELECT competency_id, name FROM learning_hub.competencies
             WHERE NOT is_deleted AND status = 'ACTIVE'
             ORDER BY sort_no, competency_id
             LIMIT ?
            """, (rs, row) -> new Choice(rs.getLong(1), rs.getString(2)), limit);
    }

    /** The draft revision's title, summary, level and objectives; empty when the revision is gone. */
    public Optional<CourseBrief> courseBrief(long revisionId) {
        List<String> objectives = jdbc.queryForList("""
            SELECT description FROM learning_hub.course_objectives WHERE revision_id = ? ORDER BY sort_no
            """, String.class, revisionId);
        return jdbc.query("""
            SELECT title, summary, level FROM learning_hub.course_revisions WHERE revision_id = ?
            """, (rs, row) -> new CourseBrief(rs.getString(1), rs.getString(2), rs.getString(3), objectives),
            revisionId).stream().findFirst();
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

    /**
     * The active units of the subtree under {@code ownerOrgId} (the unit itself first) and their active teams, what a
     * course of that unit may be assigned to before the author's own scope is checked. At most {@code limit} of each.
     */
    public List<Unit> units(long ownerOrgId, int limit) {
        List<Unit> units = new ArrayList<>(jdbc.query("""
            WITH RECURSIVE subtree(org_id, depth) AS (
                SELECT org_id, 0 FROM learning_hub.orgs WHERE org_id = ? AND status = 'active'
                UNION
                SELECT o.org_id, s.depth + 1 FROM learning_hub.orgs o JOIN subtree s ON o.parent_id = s.org_id
                 WHERE o.status = 'active'
            )
            SELECT o.org_id, o.name FROM subtree s JOIN learning_hub.orgs o ON o.org_id = s.org_id
             ORDER BY s.depth, o.sort_no, o.name, o.org_id
             LIMIT ?
            """, (rs, row) -> new Unit("org", rs.getLong(1), rs.getString(2), rs.getLong(1)), ownerOrgId, limit));
        List<Long> orgIds = units.stream().map(Unit::id).toList();
        if (orgIds.isEmpty()) {
            return units;
        }
        String marks = String.join(",", Collections.nCopies(orgIds.size(), "?"));
        List<Object> arguments = new ArrayList<>(orgIds);
        arguments.add(limit);
        units.addAll(jdbc.query("SELECT team_id, name, org_id FROM learning_hub.teams WHERE status = 'active' "
            + "AND org_id IN (" + marks + ") ORDER BY name, team_id LIMIT ?",
            (rs, row) -> new Unit("team", rs.getLong(1), rs.getString(2), rs.getLong(3)), arguments.toArray()));

        return units;
    }

    /** The draft revision as the pre-publish check reads it; empty when the revision is gone. */
    public Optional<DraftSnapshot> draftSnapshot(long revisionId) {
        boolean objective = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM learning_hub.course_objectives
                            WHERE revision_id = ? AND btrim(description) <> '')
            """, Boolean.class, revisionId));
        Map<Long, List<BlockSnapshot>> blocks = new HashMap<>();
        jdbc.query("""
            SELECT b.lesson_id, b.block_type, b.body,
                   (SELECT count(*) FROM learning_hub.process_steps s WHERE s.block_id = b.block_id),
                   (SELECT count(*) FROM learning_hub.block_assets a WHERE a.block_id = b.block_id)
              FROM learning_hub.lesson_blocks b
              JOIN learning_hub.course_lessons l ON l.lesson_id = b.lesson_id
              JOIN learning_hub.course_chapters c ON c.chapter_id = l.chapter_id
             WHERE c.revision_id = ?
             ORDER BY b.sort_no, b.block_id
            """, (RowCallbackHandler) rs -> blocks
            .computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
            .add(new BlockSnapshot(rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5))), revisionId);
        List<LessonSnapshot> lessons = jdbc.query("""
            SELECT l.lesson_id, l.lesson_type, l.quiz_id, l.scorm_package_id
              FROM learning_hub.course_lessons l
              JOIN learning_hub.course_chapters c ON c.chapter_id = l.chapter_id
             WHERE c.revision_id = ?
             ORDER BY c.sort_no, l.sort_no, l.lesson_id
            """, (rs, row) -> new LessonSnapshot(rs.getString(2), rs.getObject(3, Long.class),
            rs.getObject(4, Long.class), blocks.getOrDefault(rs.getLong(1), List.of())), revisionId);
        // The final exam as the course detail reads it: the exam link overrides the completion rule's legacy column.
        Long finalQuizId = jdbc.query("""
            SELECT COALESCE(
                (SELECT assessment_revision_id FROM learning_hub.course_assessments
                  WHERE revision_id = ? AND placement = 'final' ORDER BY link_id DESC LIMIT 1),
                (SELECT final_quiz_id FROM learning_hub.course_completion_rules WHERE revision_id = ? LIMIT 1))
            """, (rs, row) -> rs.getObject(1, Long.class), revisionId, revisionId).stream().findFirst().orElse(null);

        return jdbc.query("""
            SELECT title, program_id, level FROM learning_hub.course_revisions WHERE revision_id = ?
            """, (rs, row) -> new DraftSnapshot(rs.getString(1), rs.getObject(2, Long.class), rs.getString(3),
            objective, lessons, finalQuizId), revisionId).stream().findFirst();
    }
}
