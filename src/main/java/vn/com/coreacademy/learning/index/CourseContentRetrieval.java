package vn.com.coreacademy.learning.index;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.common.search.VectorLiteral;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;
import vn.com.coreacademy.security.SecurityContextUtils;

/**
 * Finds lesson text for the signed-in user. The access rule is the one of {@code LearningAccessPolicy}: a manager
 * (global, unit, team, or the owner of an own-scope course) reads their courses, anyone else only the revision they
 * have an active enrolment on. The rule is part of the same statement as the ordering and the limit, so chunks the
 * user cannot read never take a place in the top results.
 *
 * <p>Chunks are kept for the published revision and for every revision with an active enrolment. A manager finds the
 * published revision only; a learner finds the revision of their own enrolment, never a newer one. While the
 * published revision is not indexed yet, the newest indexed one answers in its place (the "served" revision).
 */
@Service
@RequiredArgsConstructor
public class CourseContentRetrieval {
    private static final Long NO_ID = -1L;

    private static final String SELECT = """
        SELECT ch.chunk_id, ch.course_id, r.title AS course_title, ch.revision_id, ch.chapter_id, ch.chapter_title,
               ch.lesson_id, ch.lesson_title, ch.block_id, ch.block_title, ch.content, ch.page_number,
               1 - (ch.embedding <=> CAST(:vector AS vector)) AS similarity
          FROM learning_hub.course_content_chunks ch
          JOIN learning_hub.courses course ON course.course_id = ch.course_id
          JOIN LATERAL (SELECT i.revision_id FROM learning_hub.course_content_index i
                         WHERE i.course_id = course.course_id AND i.status = 'done'
                           AND i.revision_id <= course.published_revision_id
                         ORDER BY (i.revision_id = course.published_revision_id) DESC, i.revision_id DESC
                         LIMIT 1) served ON true
          JOIN learning_hub.course_revisions r ON r.revision_id = ch.revision_id
        """;

    // A manager reads one revision of the course (MANAGER_REVISION); a learner the revision of their enrolment.
    private static final String READABLE_TEMPLATE = """
         WHERE ((MANAGER_REVISION_RULE AND (:learningGlobal = true OR course.owner_org_id IN (:learningOrgs)
                OR course.owner_team_id IN (:learningTeams)
                OR (:learningOwn = true AND course.owner_user_id = :learningUser
                    AND course.owner_org_id = :learningOrg)))
                OR (course.lifecycle_status = 'active' AND EXISTS (
                    SELECT 1 FROM learning_hub.course_enrollments e
                     WHERE e.user_id = :learningUser AND LEARNER_REVISION_RULE AND e.status <> 'withdrawn'
                       AND (EXISTS (SELECT 1 FROM learning_hub.assignment_recipients ar
                                      JOIN learning_hub.course_assignments a ON a.assignment_id = ar.assignment_id
                                     WHERE ar.enrollment_id = e.enrollment_id AND ar.status = 'active'
                                       AND a.status = 'active' AND ar.effective_start_at <= current_timestamp)
                            OR (e.self_enrolled_at IS NOT NULL AND (course.owner_org_id = :learningOrg OR EXISTS (
                                SELECT 1 FROM learning_hub.course_shares s
                                 WHERE s.course_id = course.course_id AND s.status = 'active' AND s.can_self_enroll
                                   AND (s.org_id = :learningOrg
                                        OR (s.include_descendants AND s.org_id IN (:learningAncestors))))))))))
        """;

    /** For chunks: the served revision for managers; the enrolment revision, or the served one if it has no index. */
    private static final String READABLE = READABLE_TEMPLATE
        .replace("MANAGER_REVISION_RULE", "ch.revision_id = served.revision_id")
        .replace("LEARNER_REVISION_RULE", "(e.revision_id = ch.revision_id OR (ch.revision_id = served.revision_id"
            + " AND NOT EXISTS (SELECT 1 FROM learning_hub.course_content_index x"
            + " WHERE x.revision_id = e.revision_id AND x.status = 'done')))");

    /** For a lesson read straight from the database: the published revision, or the learner's own. */
    private static final String READABLE_LESSON = READABLE_TEMPLATE
        .replace("MANAGER_REVISION_RULE", "ch.revision_id = course.published_revision_id")
        .replace("LEARNER_REVISION_RULE", "e.revision_id = ch.revision_id");

    private final NamedParameterJdbcTemplate jdbc;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;

    /** A chunk of a published lesson with what a citation needs. */
    public record CourseHit(long chunkId, long courseId, String courseTitle, long revisionId, long chapterId,
                            String chapterTitle, long lessonId, String lessonTitle, long blockId, String blockTitle,
                            String content, Integer page, double similarity) {
        /** Where the lesson is read in the app. */
        public String url() {
            return "/lesson/" + courseId + "/" + lessonId;
        }
    }

    /** A lesson the caller may read. */
    public record LessonRef(long courseId, long revisionId, long chapterId, long lessonId, String courseTitle,
                            String chapterTitle, String lessonTitle) {
    }

    private static final RowMapper<CourseHit> HITS = (rs, rowNum) -> new CourseHit(rs.getLong("chunk_id"),
        rs.getLong("course_id"), rs.getString("course_title"), rs.getLong("revision_id"), rs.getLong("chapter_id"),
        rs.getString("chapter_title"), rs.getLong("lesson_id"), rs.getString("lesson_title"), rs.getLong("block_id"),
        rs.getString("block_title"), rs.getString("content"), rs.getObject("page_number", Integer.class),
        rs.getDouble("similarity"));

    /** The {@code limit} chunks closest to {@code vector} among those the caller may read, best first. */
    public List<CourseHit> search(float[] vector, int limit) {
        Map<String, Object> parameters = readerParameters();
        parameters.put("vector", VectorLiteral.encode(vector));
        parameters.put("limit", limit);

        return jdbc.query(SELECT + READABLE
            + " ORDER BY ch.embedding <=> CAST(:vector AS vector), ch.chunk_id LIMIT :limit", parameters, HITS);
    }

    /** The {@code limit} chunks of one lesson closest to {@code vector}, if the caller may read that lesson. */
    public List<CourseHit> searchInLesson(float[] vector, long lessonId, int limit) {
        Map<String, Object> parameters = readerParameters();
        parameters.put("vector", VectorLiteral.encode(vector));
        parameters.put("lessonId", lessonId);
        parameters.put("limit", limit);

        return jdbc.query(SELECT + READABLE + " AND ch.lesson_id = :lessonId"
            + " ORDER BY ch.embedding <=> CAST(:vector AS vector), ch.chunk_id LIMIT :limit", parameters, HITS);
    }

    /** The text of one lesson in reading order (first {@code limit} chunks), if the caller may read it. */
    public List<CourseHit> lessonChunks(long lessonId, int limit) {
        Map<String, Object> parameters = readerParameters();
        parameters.put("vector", VectorLiteral.encode(unitVector()));
        parameters.put("lessonId", lessonId);
        parameters.put("limit", limit);

        return jdbc.query(SELECT + READABLE + " AND ch.lesson_id = :lessonId"
            + " ORDER BY ch.chunk_index, ch.chunk_id LIMIT :limit", parameters, HITS);
    }

    /**
     * The lesson of the published revision of a course, if the caller may read it. The rule is the one of the chunks,
     * so a lesson that is unpublished, from an older revision or of a course the user cannot read is not found.
     */
    public Optional<LessonRef> lesson(long courseId, long revisionId, long lessonId) {
        Map<String, Object> parameters = readerParameters();
        parameters.put("courseId", courseId);
        parameters.put("revisionId", revisionId);
        parameters.put("lessonId", lessonId);

        return jdbc.query("""
            SELECT course.course_id, ch.revision_id, ch.chapter_id, l.lesson_id, r.title AS course_title,
                   ch.title AS chapter_title, l.title AS lesson_title
              FROM learning_hub.course_lessons l
              JOIN learning_hub.course_chapters ch ON ch.chapter_id = l.chapter_id
              JOIN learning_hub.course_revisions r ON r.revision_id = ch.revision_id
              JOIN learning_hub.courses course ON course.course_id = r.course_id
            """ + READABLE_LESSON + " AND course.course_id = :courseId AND ch.revision_id = :revisionId"
            + " AND l.lesson_id = :lessonId", parameters,
            (rs, rowNum) -> new LessonRef(rs.getLong("course_id"), rs.getLong("revision_id"),
                rs.getLong("chapter_id"), rs.getLong("lesson_id"), rs.getString("course_title"),
                rs.getString("chapter_title"), rs.getString("lesson_title"))).stream().findFirst();
    }

    /** One chunk by id if the caller may read it, with no similarity (1.0). */
    public Optional<CourseHit> find(long chunkId) {
        Map<String, Object> parameters = readerParameters();
        // Without a query vector the similarity column is not meaningful; a constant vector keeps the SQL shared.
        parameters.put("vector", VectorLiteral.encode(unitVector()));
        parameters.put("chunkId", chunkId);

        return jdbc.query(SELECT + READABLE + " AND ch.chunk_id = :chunkId", parameters, HITS).stream().findFirst();
    }

    private Map<String, Object> readerParameters() {
        Actor actor = identity.actor(SecurityContextUtils.getUserId());
        var scope = access.searchScope(actor);
        Long orgId = actor.person().orgId();
        var ancestors = orgId == null ? Set.<Long>of() : identity.activeAncestorIds(orgId);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("learningUser", actor.person().id());
        parameters.put("learningOrg", orgId == null ? NO_ID : orgId);
        parameters.put("learningGlobal", scope.global());
        parameters.put("learningOrgs", scope.orgIds().isEmpty() ? List.of(NO_ID) : scope.orgIds());
        parameters.put("learningTeams", scope.teamIds().isEmpty() ? List.of(NO_ID) : scope.teamIds());
        parameters.put("learningOwn", scope.own());
        parameters.put("learningAncestors", ancestors.isEmpty() ? List.of(NO_ID) : ancestors);

        return parameters;
    }

    private static float[] unitVector() {
        float[] vector = new float[1024];
        vector[0] = 1f;

        return vector;
    }
}
