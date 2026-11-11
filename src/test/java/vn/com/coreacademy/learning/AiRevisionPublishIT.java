package vn.com.coreacademy.learning;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;

/** Publishing a revision with AI-generated content needs the publisher's review confirmation, kept in the audit data. */
class AiRevisionPublishIT extends AssessmentIntegrationTest {
    private static final String PUBLISH = "/api/v1/courses/{c}/revisions/{r}/publish";
    private static final String DRAFT = "/api/v1/courses/{c}/draft";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
    }

    private record Draft(long courseId, long revisionId, long version) {
    }

    private Draft draft(boolean aiGenerated) {
        long courseId = fixtures.course().id();
        JsonNode revision = api.ok(INSTRUCTOR, api.post(Map.of(), DRAFT, courseId)).path("revision");
        long revisionId = revision.path("id").asLong();
        jdbc.update("UPDATE learning_hub.course_revisions SET ai_generated = ? WHERE revision_id = ?",
            aiGenerated, revisionId);
        return new Draft(courseId, revisionId, revision.path("rowVersion").asLong());
    }

    private Map<String, Object> body(Draft draft, Boolean reviewConfirmed) {
        Map<String, Object> body = new HashMap<>();
        body.put("expectedVersion", draft.version());
        if (reviewConfirmed != null) {
            body.put("reviewConfirmed", reviewConfirmed);
        }
        return body;
    }

    private Map<String, Object> revisionRow(Draft draft) {
        return jdbc.queryForMap("SELECT state, ai_generated, review_confirmed_by, review_confirmed_at "
            + "FROM learning_hub.course_revisions WHERE revision_id = ?", draft.revisionId());
    }

    @Test
    void anAiRevisionIsRefusedWithoutTheReviewConfirmation() {
        Draft draft = draft(true);

        for (Boolean missing : new Boolean[] {null, false}) {
            JsonNode refusal = api.error(INSTRUCTOR,
                api.post(body(draft, missing), PUBLISH, draft.courseId(), draft.revisionId()), "CORE-VALIDATION_FAILED");
            assertThat(refusal.toString()).contains("reviewConfirmed").contains("REVIEW_REQUIRED");
        }

        assertThat(revisionRow(draft)).containsEntry("state", "draft").containsEntry("review_confirmed_by", null);
    }

    @Test
    void theConfirmationIsStoredWithWhoAndWhenAndAppearsInTheAuditData() {
        Draft draft = draft(true);

        JsonNode published = api.ok(INSTRUCTOR,
            api.post(body(draft, true), PUBLISH, draft.courseId(), draft.revisionId()));

        assertThat(published.path("revision").path("aiGenerated").asBoolean()).isTrue();
        Map<String, Object> row = revisionRow(draft);
        assertThat(row).containsEntry("state", "published");
        assertThat(row.get("review_confirmed_by")).isNotNull().isEqualTo(
            jdbc.queryForObject("SELECT published_by FROM learning_hub.course_revisions WHERE revision_id = ?",
                String.class, draft.revisionId()));
        assertThat(row.get("review_confirmed_at")).isNotNull();
        String details = jdbc.queryForObject("""
            SELECT details::text FROM learning_hub.audit_log
            WHERE action = 'LEARNING_COURSE_PUBLISHED' AND entity_id = ? ORDER BY 1 DESC LIMIT 1
            """, String.class, String.valueOf(draft.courseId()));
        assertThat(details).contains("reviewConfirmedBy").contains("reviewConfirmedAt").contains("aiGenerated");
    }

    @Test
    void aRevisionWithoutTheFlagPublishesExactlyAsBefore() {
        Draft draft = draft(false);

        JsonNode published = api.ok(INSTRUCTOR,
            api.post(body(draft, null), PUBLISH, draft.courseId(), draft.revisionId()));

        assertThat(published.path("revision").path("aiGenerated").asBoolean()).isFalse();
        assertThat(revisionRow(draft)).containsEntry("state", "published").containsEntry("review_confirmed_by", null);
    }

    @Test
    void aDraftClonedFromAnAiRevisionKeepsTheFlagButNotTheConfirmation() {
        Draft draft = draft(true);
        api.ok(INSTRUCTOR, api.post(body(draft, true), PUBLISH, draft.courseId(), draft.revisionId()));

        JsonNode clone = api.ok(INSTRUCTOR, api.post(Map.of(), DRAFT, draft.courseId())).path("revision");

        assertThat(clone.path("aiGenerated").asBoolean()).isTrue();
        assertThat(clone.path("reviewConfirmedBy").isNull()).isTrue();
        assertThat(clone.path("id").asLong()).isNotEqualTo(draft.revisionId());
    }
}
