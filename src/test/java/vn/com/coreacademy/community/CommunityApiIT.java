package vn.com.coreacademy.community;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.tool.SearchCommunityTool;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;
import vn.com.coreacademy.community.entity.NotificationDigestBatch;
import vn.com.coreacademy.community.repository.CommunityBadgeRepository;
import vn.com.coreacademy.community.repository.CommunityContributionRepository;
import vn.com.coreacademy.community.repository.CommunityKnowledgeSource;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.service.CommunityFanoutService;
import vn.com.coreacademy.identity.service.IdentityWriteLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opt-in API integration suite; all writes target an isolated synthetic database. The local fallback is fixed to
 * the disposable 55439 cluster and verifies core SQL only, never vector retrieval or an external service.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfSystemProperty(named = "community.api.integration", matches = "true")
class CommunityApiIT {
    private static final String API = "/api/v1/community";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private IdentityWriteLock writeLock;

    @Autowired
    private CommunityRepository community;

    @Autowired
    private CommunityContributionRepository contributionRepository;

    @Autowired
    private CommunityBadgeRepository badgeRepository;

    @Autowired
    private CommunityFanoutService fanout;

    @Autowired
    private CommunityKnowledgeSource knowledgeSource;

    @Autowired
    private SearchCommunityTool communityTool;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (Boolean.getBoolean("community.api.local")) {
            registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + Integer.getInteger("community.api.local.port", 55439) + "/"
                + "community_api_check_20261027?currentSchema=learning_hub");
            registry.add("spring.datasource.username", () -> "codex_verify");
            registry.add("spring.datasource.password", () -> "");
        } else {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
            List<String> scripts = List.of("identity", "learning", "media_progress", "knowledge", "assessment",
                "community", "community_functions");
            for (int i = 0; i < scripts.size(); i++) {
                postgres.withCopyFileToContainer(MountableFile.forHostPath("docs/sql/learning_hub_"
                    + scripts.get(i) + ".sql"), "/docker-entrypoint-initdb.d/0" + i + ".sql");
            }
            postgres.start();
            registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "?currentSchema=learning_hub");
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
        registry.add("spring.config.import", () -> "optional:file:./tmp/community-empty.properties");
        registry.add("app.jwt.secret", () -> "synthetic-community-test-secret-at-least-32-bytes");
        registry.add("app.storage.s3.endpoint", () -> "http://127.0.0.1:1");
        registry.add("app.storage.s3.access-key", () -> "synthetic-test-access");
        registry.add("app.storage.s3.secret-key", () -> "synthetic-test-secret");
        registry.add("app.knowledge.worker.poll-interval", () -> "1h");
        registry.add("app.learning.course-index.poll-interval", () -> "1h");
        registry.add("app.assistant.generation.poll-interval", () -> "1h");
        registry.add("app.knowledge.ollama.base-url", () -> "http://127.0.0.1:1");
        registry.add("app.knowledge.storage-dir", () -> "./tmp/community-knowledge-test");
        registry.add("app.bootstrap.admin-id", () -> "");
        registry.add("app.bootstrap.admin-user", () -> "");
        registry.add("app.bootstrap.admin-password", () -> "");
    }

    @BeforeEach
    void syntheticCatalog() {
        // Only the isolated database above is reachable; no reset of real accounts, passwords or Identity roles.
        Long org = jdbc.queryForObject("select org_id from learning_hub.users where user_id = '000005'", Long.class);
        jdbc.update("""
            insert into learning_hub.community_tags
              (owner_org_id, slug, name, status, created_by, updated_by)
            values (?, ?, 'Spring Boot test', 'active', '000005', '000005')
            """, org, "spring-" + UUID.randomUUID());
    }

    @Test
    void draftIsPrivateAndPublishRequiresTags() throws Exception {
        JsonNode created = create("000005", "questions", "organization", List.of());
        long id = created.path("contentId").asLong();
        assertThat(response(get(API + "/contents/" + id), "000004").path("status").asText()).isEqualTo("ERROR");
        assertThat(response(post(API + "/contents/" + id + "/publish").content(json.writeValueAsString(
            versions(created))), "000005").path("messageCode").asText()).isEqualTo("COMMUNITY-NOT_PUBLISHABLE");
        assertThat(response(get(API + "/questions"), "000004").path("data").path("items")
            .findValuesAsText("contentId")).doesNotContain(Long.toString(id));
    }

    @Test
    void baselineContains51TablesAnd670ColumnsWithoutPhysicalForeignKeys() {
        assertThat(jdbc.queryForObject("""
            select count(distinct table_name) from information_schema.columns
            where table_schema = 'learning_hub' and table_name like 'community_%'
            """, Long.class)).isEqualTo(51);
        assertThat(jdbc.queryForObject("""
            select count(*) from information_schema.columns
            where table_schema = 'learning_hub' and table_name like 'community_%'
            """, Long.class)).isEqualTo(670);
        assertThat(jdbc.queryForObject("""
            select count(*) from pg_constraint k join pg_class t on t.oid = k.conrelid
            join pg_namespace n on n.oid = t.relnamespace
            where n.nspname = 'learning_hub' and t.relname like 'community_%' and k.contype in ('f', 'u')
            """, Long.class)).isZero();
        if (!Boolean.getBoolean("community.api.local")) {
            assertThat(jdbc.queryForObject("""
                select format_type(a.atttypid, a.atttypmod) from pg_attribute a
                where a.attrelid = 'learning_hub.community_search_chunks'::regclass and a.attname = 'embedding'
                """, String.class)).isEqualTo("vector(1024)");
        }
    }

    @Test
    void retryIsIdempotentAndDifferentPayloadConflicts() throws Exception {
        Map<String, Object> body = body("organization", List.of());
        JsonNode first = response(post(API + "/articles").content(json.writeValueAsString(body)), "000005");
        JsonNode replay = response(post(API + "/articles").content(json.writeValueAsString(body)), "000005");
        assertThat(replay.path("data").path("contentId")).isEqualTo(first.path("data").path("contentId"));
        body.put("title", "Changed request under the same key");
        assertThat(response(post(API + "/articles").content(json.writeValueAsString(body)), "000005")
            .path("messageCode").asText()).isEqualTo("COMMUNITY-IDEMPOTENCY_CONFLICT");
    }

    @Test
    void staleDraftCannotOverwritePublishedContent() throws Exception {
        JsonNode created = create("000005", "articles", "organization", List.of());
        long id = created.path("contentId").asLong();
        publish(id, created, "000005");
        var update = Map.of("expectedContentVersion", created.path("contentVersion").asLong(),
            "expectedRevisionVersion", 0, "title", "Overwriting published is forbidden",
            "bodyMarkdown", "This update must not mutate a published revision.", "assets", List.of());
        assertThat(response(put(API + "/contents/" + id + "/draft").content(json.writeValueAsString(update)), "000005")
            .path("status").asText()).isEqualTo("ERROR");
        JsonNode reader = response(get(API + "/contents/" + id + "?includeOwnDraft=true"), "000004").path("data");
        assertThat(reader.path("draftRevision").isNull()).isTrue();
        assertThat(reader.path("publishedRevision").path("title").asText()).isEqualTo("Synthetic test article");
    }

    @Test
    void voteIsSetAndCannotSelfVote() throws Exception {
        JsonNode created = create("000005", "articles", "organization", List.of());
        long id = created.path("contentId").asLong();
        publish(id, created, "000005");
        for (int i = 0; i < 2; i++) {
            assertThat(response(put(API + "/contents/" + id + "/vote").content("{\"value\":1}"), "000004")
                .path("data").path("score").asInt()).isEqualTo(1);
        }
        assertThat(response(put(API + "/contents/" + id + "/vote").content("{\"value\":1}"), "000005")
            .path("status").asText()).isEqualTo("ERROR");
        assertThat(response(put(API + "/contents/" + id + "/vote").content("{\"value\":0}"), "000004")
            .path("data").path("score").asInt()).isZero();
    }

    @Test
    void acceptPinsRevisionAndDeleteClearsItAtomically() throws Exception {
        Long tagId = jdbc.queryForObject("select max(tag_id) from learning_hub.community_tags", Long.class);
        JsonNode question = create("000005", "questions", "organization", List.of(tagId));
        long qid = question.path("contentId").asLong();
        question = publish(qid, question, "000005");
        Map<String, Object> answerBody = body(null, List.of());
        answerBody.remove("visibility");
        answerBody.remove("tagIds");
        JsonNode answer = success(post(API + "/questions/" + qid + "/answers")
            .content(json.writeValueAsString(answerBody)), "000004");
        long aid = answer.path("contentId").asLong();
        answer = publish(aid, answer, "000004");
        assertThat(answer.path("questionId").asLong()).isEqualTo(qid);
        long revision = answer.path("publishedRevision").path("revisionId").asLong();
        question = success(put(API + "/questions/" + qid + "/accepted-answer").content(json.writeValueAsString(
            Map.of("expectedContentVersion", question.path("contentVersion").asLong(),
                "answerContentId", aid, "answerRevisionId", revision))), "000005");
        assertThat(question.path("resolutionStatus").asText()).isEqualTo("solved");
        JsonNode draft = success(post(API + "/contents/" + aid + "/draft").content(json.writeValueAsString(
            Map.of("expectedContentVersion", answer.path("contentVersion").asLong()))), "000004");
        answer = success(post(API + "/contents/" + aid + "/publish").content(json.writeValueAsString(
            Map.of("expectedContentVersion", draft.path("contentVersion").asLong(),
                "expectedRevisionVersion", draft.path("revisionVersion").asLong()))), "000004");
        question = success(get(API + "/questions/" + qid), "000005");
        assertThat(question.path("acceptedRevisionId").asLong()).isEqualTo(revision);
        assertThat(question.path("acceptedRevisionIsCurrent").asBoolean()).isFalse();
        success(post(API + "/contents/" + aid + "/lifecycle").content(json.writeValueAsString(
            Map.of("expectedContentVersion", answer.path("contentVersion").asLong(),
                "action", "soft_delete", "reason", "Synthetic deletion test"))), "000004");
        question = success(get(API + "/questions/" + qid), "000005");
        assertThat(question.path("acceptedAnswerId").isNull()).isTrue();
        assertThat(question.path("resolutionStatus").asText()).isEqualTo("unanswered");
    }

    @Test
    void restrictedContentNeverLeaksThroughListOrSuggestion() throws Exception {
        Long tagId = jdbc.queryForObject("select max(tag_id) from learning_hub.community_tags", Long.class);
        JsonNode question = create("000005", "questions", "restricted", List.of(tagId));
        long id = question.path("contentId").asLong();
        publish(id, question, "000005");
        assertThat(response(get(API + "/questions/" + id), "000004").path("status").asText()).isEqualTo("ERROR");
        JsonNode suggestions = success(get(API + "/questions/suggestions?q=Synthetic%20test"), "000004");
        assertThat(suggestions.findValues("contentId")).doesNotContain(json.valueToTree(id));
        assertThat(success(get(API + "/questions?tagIds=" + tagId), "000004").path("totalElements").asLong()).isZero();
    }

    @Test
    void commentDepthTombstoneAndCrossContentParentAreChecked() throws Exception {
        JsonNode article = create("000005", "articles", "organization", List.of());
        long id = article.path("contentId").asLong();
        publish(id, article, "000005");
        JsonNode first = comment(id, null);
        JsonNode second = comment(id, first.path("commentId").asLong());
        JsonNode third = comment(id, second.path("commentId").asLong());
        var body = Map.of("clientRequestId", UUID.randomUUID(), "bodyMarkdown", "Too deep",
            "parentCommentId", third.path("commentId").asLong());
        assertThat(response(post(API + "/contents/" + id + "/comments").content(json.writeValueAsString(body)), "000004")
            .path("status").asText()).isEqualTo("ERROR");
        success(delete(API + "/comments/" + first.path("commentId").asLong() + "?expectedVersion="
            + first.path("version").asLong()), "000004");
        JsonNode list = success(get(API + "/contents/" + id + "/comments"), "000005").path("items");
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.get(0).path("bodyMarkdown").isNull()).isTrue();
    }

    @Test
    void unknownFieldsAndUnauthenticatedRequestsAreRejected() throws Exception {
        var body = body("organization", List.of());
        body.put("authorUserId", "000001");
        assertThat(response(post(API + "/articles").content(json.writeValueAsString(body)), "000005")
            .path("status").asText()).isEqualTo("ERROR");
        mvc.perform(get(API + "/articles")).andExpect(status().isUnauthorized());
    }

    @Test
    void guildInvitationDoesNotOpenContentUntilAcceptedAndLeaveRevokesIt() throws Exception {
        JsonNode guild = success(post(API + "/guilds").content(json.writeValueAsString(Map.of(
            "clientRequestId", UUID.randomUUID(), "slug", "private-" + UUID.randomUUID(),
            "name", "Synthetic private guild", "visibility", "private", "joinPolicy", "invite"))), "000005");
        long gid = guild.path("guildId").asLong();
        assertThat(response(get(API + "/guilds/" + gid), "000004").path("status").asText()).isEqualTo("ERROR");
        JsonNode invitations = success(post(API + "/guilds/" + gid + "/invitations").content(json.writeValueAsString(
            Map.of("userIds", List.of("000004")))), "000005");
        JsonNode invitation = invitations.get(0);
        var articleBody = body("guild", List.of());
        articleBody.put("primaryGuildId", gid);
        JsonNode article = success(post(API + "/articles").content(json.writeValueAsString(articleBody)), "000005");
        long aid = article.path("contentId").asLong();
        publish(aid, article, "000005");
        assertThat(response(get(API + "/contents/" + aid), "000004").path("status").asText()).isEqualTo("ERROR");
        JsonNode member = success(post(API + "/guilds/" + gid + "/invitation-response").content(json.writeValueAsString(
            Map.of("expectedVersion", invitation.path("version").asLong(), "action", "accept"))), "000004");
        success(get(API + "/contents/" + aid), "000004");
        success(post(API + "/guilds/" + gid + "/leave").content(json.writeValueAsString(
            Map.of("expectedVersion", member.path("version").asLong()))), "000004");
        assertThat(response(get(API + "/contents/" + aid), "000004").path("status").asText()).isEqualTo("ERROR");
    }

    @Test
    void ownerCannotLeaveAndTransferIsAtomic() throws Exception {
        JsonNode guild = success(post(API + "/guilds").content(json.writeValueAsString(Map.of(
            "clientRequestId", UUID.randomUUID(), "slug", "open-" + UUID.randomUUID(),
            "name", "Synthetic open guild", "visibility", "organization", "joinPolicy", "open"))), "000005");
        long id = guild.path("guildId").asLong();
        assertThat(response(post(API + "/guilds/" + id + "/leave").content(json.writeValueAsString(
            Map.of("expectedVersion", guild.path("membership").path("version").asLong()))), "000005")
            .path("status").asText()).isEqualTo("ERROR");
        success(post(API + "/guilds/" + id + "/join"), "000004");
        guild = success(post(API + "/guilds/" + id + "/transfer-owner").content(json.writeValueAsString(
            Map.of("expectedVersion", guild.path("version").asLong(), "successorUserId", "000004"))), "000005");
        assertThat(guild.path("ownerUserId").asText()).isEqualTo("000004");
        assertThat(jdbc.queryForObject("""
            select count(*) from learning_hub.community_guild_members
            where guild_id = ? and member_role = 'owner' and status = 'active'
            """, Long.class, id)).isEqualTo(1);
    }

    @Test
    void successfulDraftSaveAdvancesVersionsAndStaleSaveCannotOverwrite() throws Exception {
        JsonNode article = create("000005", "articles", "organization", List.of());
        long id = article.path("contentId").asLong();
        var body = new HashMap<String, Object>(versions(article));
        body.put("title", "Updated synthetic article");
        body.put("bodyMarkdown", "The first edit must remain when the second request uses a stale version.");
        body.put("assets", List.of());
        JsonNode updated = success(put(API + "/contents/" + id + "/draft").content(json.writeValueAsString(body)), "000005");
        assertThat(updated.path("contentVersion").asLong()).isGreaterThan(article.path("contentVersion").asLong());
        body.put("title", "Stale overwrite");
        assertThat(response(put(API + "/contents/" + id + "/draft").content(json.writeValueAsString(body)), "000005")
            .path("messageCode").asText()).isEqualTo("COMMUNITY-CONFLICT");
    }

    @Test
    void moderatorGrantNeedsAdminAndCannotBeSelfGranted() throws Exception {
        Long org = jdbc.queryForObject("select org_id from learning_hub.users where user_id = '000005'", Long.class);
        String body = json.writeValueAsString(Map.of("orgId", org, "active", true,
            "expectedVersion", 0, "reason", "Synthetic moderator grant check"));
        mvc.perform(put(API + "/moderation/moderators/000004").with(jwt().jwt(token -> token.subject("000005")))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        success(put(API + "/moderation/moderators/000005").content(body), "000003");
        mvc.perform(put(API + "/moderation/moderators/000003").with(jwt().jwt(token -> token.subject("000003")))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
    }

    @Test
    void reportDetailHidesReporterNoteUntilAssignedAndModeratorsAreListedInScope() throws Exception {
        moderator("000005");
        moderator("000006");
        JsonNode article = create("000004", "articles", "organization", List.of());
        long content = article.path("contentId").asLong();
        publish(content, article, "000004");
        long id = success(post(API + "/reports").content(json.writeValueAsString(Map.of(
            "contentId", content, "reasonCode", "sensitive", "description", "Synthetic private note"))), "000004")
            .path("reportId").asLong();
        JsonNode unassigned = success(get(API + "/moderation/reports/" + id), "000006");
        assertThat(unassigned.path("targetId").asLong()).isEqualTo(content);
        assertThat(unassigned.hasNonNull("description") || unassigned.hasNonNull("reporter")).isFalse();
        success(post(API + "/moderation/reports/" + id + "/assign").content(json.writeValueAsString(Map.of(
            "expectedVersion", unassigned.path("version").asLong(), "moderatorUserId", "000006"))), "000006");
        assertThat(success(get(API + "/moderation/reports/" + id), "000006").path("description").asText())
            .isEqualTo("Synthetic private note");
        assertThat(success(get(API + "/moderation/reports/" + id), "000005").hasNonNull("description")).isFalse();
        mvc.perform(get(API + "/moderation/reports/" + id).with(jwt().jwt(token -> token.subject("000004"))))
            .andExpect(status().isForbidden());

        String org = jdbc.queryForObject("select org_id::text from learning_hub.users where user_id = '000006'",
            String.class);
        JsonNode admin = success(get(API + "/moderation/moderators").param("orgId", org), "000003");
        assertThat(admin.path("items").findValuesAsText("userId")).contains("000005", "000006");
        assertThat(success(get(API + "/moderation/moderators").param("orgId", org), "000006")
            .path("totalElements").asLong()).isEqualTo(admin.path("totalElements").asLong());
        mvc.perform(get(API + "/moderation/moderators").param("orgId", org)
            .with(jwt().jwt(token -> token.subject("000004")))).andExpect(status().isForbidden());
    }

    @Test
    void detailCarriesSavedFollowDuplicateClosureAndLifecycleFlags() throws Exception {
        Long tagId = jdbc.queryForObject("select max(tag_id) from learning_hub.community_tags", Long.class);
        JsonNode original = create("000005", "questions", "organization", List.of(tagId));
        long originalId = original.path("contentId").asLong();
        publish(originalId, original, "000005");
        JsonNode created = create("000004", "questions", "organization", List.of(tagId));
        long id = created.path("contentId").asLong();
        JsonNode published = publish(id, created, "000004");
        assertThat(published.path("capabilities").path("canClose").asBoolean()).isTrue();
        assertThat(published.path("capabilities").path("canMarkDuplicate").asBoolean()).isTrue();
        assertThat(published.path("capabilities").path("canDelete").asBoolean()).isTrue();
        assertThat(published.path("capabilities").path("canReopen").asBoolean()).isFalse();

        JsonNode reader = success(get(API + "/contents/" + id), "000005");
        JsonNode flags = reader.path("capabilities");
        assertThat(flags.path("canClose").asBoolean() || flags.path("canArchive").asBoolean()
            || flags.path("canDelete").asBoolean() || flags.path("canMarkDuplicate").asBoolean()).isFalse();
        assertThat(reader.path("saved").asBoolean()).isFalse();
        assertThat(reader.hasNonNull("subscriptionMode")).isFalse();
        success(put(API + "/me/bookmarks/" + id).content(json.writeValueAsString(Map.of("saved", true))), "000005");
        success(put(API + "/me/subscriptions").content(json.writeValueAsString(Map.of(
            "contentId", id, "mode", "instant"))), "000005");
        reader = success(get(API + "/contents/" + id), "000005");
        assertThat(reader.path("saved").asBoolean()).isTrue();
        assertThat(reader.path("subscriptionMode").asText()).isEqualTo("instant");

        JsonNode closed = success(put(API + "/questions/" + id + "/duplicate").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", published.path("contentVersion").asLong(), "targetQuestionId", originalId,
            "reason", "Synthetic duplicate reason"))), "000004");
        assertThat(closed.path("isDuplicate").asBoolean()).isTrue();
        assertThat(closed.path("duplicateTarget").path("contentId").asLong()).isEqualTo(originalId);
        assertThat(closed.path("closure").path("action").asText()).isEqualTo("duplicate");
        assertThat(closed.path("closure").path("reason").asText()).isEqualTo("Synthetic duplicate reason");
        assertThat(closed.path("closure").path("actor").path("userId").asText()).isEqualTo("000004");
        assertThat(closed.path("capabilities").path("canReopen").asBoolean()).isTrue();
        assertThat(closed.path("capabilities").path("canClose").asBoolean()).isFalse();
    }

    @Test
    void reopenDropsTheDuplicateMarkAndClosureFollowsTheClosingEvent() throws Exception {
        Long tagId = jdbc.queryForObject("select max(tag_id) from learning_hub.community_tags", Long.class);
        JsonNode original = create("000005", "questions", "organization", List.of(tagId));
        publish(original.path("contentId").asLong(), original, "000005");
        JsonNode created = create("000004", "questions", "organization", List.of(tagId));
        long id = created.path("contentId").asLong();
        JsonNode question = publish(id, created, "000004");
        question = success(put(API + "/questions/" + id + "/duplicate").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", question.path("contentVersion").asLong(),
            "targetQuestionId", original.path("contentId").asLong(), "reason", "Synthetic duplicate reason"))),
            "000004");

        question = success(post(API + "/contents/" + id + "/lifecycle").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", question.path("contentVersion").asLong(), "action", "reopen",
            "reason", "Synthetic reopen reason"))), "000004");
        assertThat(question.path("lifecycleStatus").asText()).isEqualTo("active");
        assertThat(question.path("isDuplicate").asBoolean()).isFalse();
        assertThat(question.hasNonNull("duplicateTarget")).isFalse();

        // Bỏ đánh dấu, archive hay mở lại sau lần đóng không được thành "lần đóng".
        question = success(post(API + "/contents/" + id + "/lifecycle").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", question.path("contentVersion").asLong(), "action", "close",
            "reason", "Synthetic plain close"))), "000004");
        question = success(post(API + "/contents/" + id + "/lifecycle").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", question.path("contentVersion").asLong(), "action", "archive",
            "reason", "Synthetic archive"))), "000004");
        success(post(API + "/contents/" + id + "/lifecycle").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", question.path("contentVersion").asLong(), "action", "unarchive",
            "reason", "Synthetic unarchive"))), "000004");
        JsonNode closure = success(get(API + "/contents/" + id), "000005").path("closure");
        assertThat(closure.path("action").asText()).isEqualTo("close");
        assertThat(closure.path("reason").asText()).isEqualTo("Synthetic plain close");
        assertThat(closure.path("actor").path("userId").asText()).isEqualTo("000004");
        assertThat(closure.hasNonNull("occurredAt")).isTrue();
    }

    @Test
    void moderatorClosureNamesNoModeratorAndNoCaseReason() throws Exception {
        moderator("000006");
        Long tagId = jdbc.queryForObject("select max(tag_id) from learning_hub.community_tags", Long.class);
        JsonNode created = create("000004", "questions", "organization", List.of(tagId));
        long id = created.path("contentId").asLong();
        publish(id, created, "000004");
        JsonNode report = success(post(API + "/reports").content(json.writeValueAsString(Map.of(
            "contentId", id, "reasonCode", "outdated", "description", "Synthetic moderation case"))), "000005");
        long reportId = report.path("reportId").asLong();
        report = success(post(API + "/moderation/reports/" + reportId + "/assign").content(json.writeValueAsString(
            Map.of("expectedVersion", report.path("version").asLong(), "moderatorUserId", "000006"))), "000006");
        JsonNode evidence = success(get(API + "/moderation/reports/" + reportId + "/evidence"), "000006");
        success(post(API + "/moderation/reports/" + reportId + "/actions").content(json.writeValueAsString(Map.of(
            "expectedReportVersion", report.path("version").asLong(),
            "expectedTargetVersion", evidence.path("targetVersion").asLong(),
            "action", "close", "reason", "Synthetic wording from the report case"))), "000006");

        for (String reader : List.of("000004", "000005")) {
            JsonNode detail = success(get(API + "/contents/" + id), reader);
            assertThat(detail.path("closure").path("action").asText()).isEqualTo("moderated");
            assertThat(detail.path("closure").hasNonNull("actor")).isFalse();
            assertThat(detail.path("closure").hasNonNull("reason")).isFalse();
        }
        assertThat(success(get(API + "/contents/" + id), "000004").path("capabilities").path("canReopen")
            .asBoolean()).isFalse();
    }

    @Test
    void revokedModeratorIsGrantedAgainWithTheRevokedGrantVersion() throws Exception {
        Long org = jdbc.queryForObject("select org_id from learning_hub.users where user_id = '000002'", Long.class);
        JsonNode granted = success(put(API + "/moderation/moderators/000002").content(json.writeValueAsString(Map.of(
            "orgId", org, "active", true, "expectedVersion", 0, "reason", "Synthetic regrant fixture"))), "000003");
        JsonNode revoked = success(put(API + "/moderation/moderators/000002").content(json.writeValueAsString(Map.of(
            "orgId", org, "active", false, "expectedVersion", granted.path("version").asLong(),
            "reason", "Synthetic revoke"))), "000003");
        JsonNode listed = success(get(API + "/moderation/moderators").param("orgId", org.toString())
            .param("status", "revoked").param("size", "100"), "000003").path("items").findParents("userId").stream()
            .filter(item -> "000002".equals(item.path("userId").asText())).findFirst().orElseThrow();
        assertThat(listed.path("version").asLong()).isEqualTo(revoked.path("version").asLong());

        assertThat(response(put(API + "/moderation/moderators/000002").content(json.writeValueAsString(Map.of(
            "orgId", org, "active", true, "expectedVersion", 0, "reason", "Synthetic stale regrant"))), "000003")
            .path("messageCode").asText()).isEqualTo("COMMUNITY-CONFLICT");
        JsonNode again = success(put(API + "/moderation/moderators/000002").content(json.writeValueAsString(Map.of(
            "orgId", org, "active", true, "expectedVersion", listed.path("version").asLong(),
            "reason", "Synthetic regrant"))), "000003");
        assertThat(again.path("status").asText()).isEqualTo("active");
    }

    @Test
    void moderatorMenuComesFromTheGrantOnceAndCandidatesStayInTheOwnUnit() throws Exception {
        moderator("000006");
        JsonNode me = success(get("/api/v1/auth/me"), "000006");
        assertThat(me.path("functionDetails").findValuesAsText("code")).contains("COMMUNITY_MODERATION");
        assertThat(me.path("functionScopes").path("COMMUNITY_MODERATION")).hasSize(1);

        // ADMIN của Ban vừa có chức năng theo vai trò vừa là người điều phối: scope ORG chỉ xuất hiện một lần.
        moderatorBy("000003", "000001");
        assertThat(success(get("/api/v1/auth/me"), "000003").path("functionScopes")
            .path("COMMUNITY_MODERATION")).hasSize(1);

        jdbc.update("""
            insert into learning_hub.orgs(parent_id, org_type, code, name, status, sort_no)
            values (null, 'UNIT', ?, 'Synthetic other unit', 'active', 99)
            """, "SYN-" + UUID.randomUUID().toString().substring(0, 8));
        String other = jdbc.queryForObject("select max(org_id)::text from learning_hub.orgs", String.class);
        mvc.perform(get(API + "/moderation/candidates").param("orgId", other)
            .with(jwt().jwt(token -> token.subject("000006")))).andExpect(status().isForbidden());
    }

    private void moderatorBy(String userId, String admin) throws Exception {
        Long org = jdbc.queryForObject("select org_id from learning_hub.users where user_id = ?", Long.class, userId);
        success(put(API + "/moderation/moderators/" + userId).content(json.writeValueAsString(Map.of(
            "orgId", org, "active", true, "expectedVersion", 0, "reason", "Synthetic API test fixture"))), admin);
    }

    @Test
    void draftChangeNotePendingDraftReportTargetAndCandidates() throws Exception {
        JsonNode article = create("000005", "articles", "organization", List.of());
        long id = article.path("contentId").asLong();
        JsonNode published = publish(id, article, "000005");
        JsonNode draft = success(post(API + "/contents/" + id + "/draft").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", published.path("contentVersion").asLong()))), "000005");
        JsonNode saved = success(put(API + "/contents/" + id + "/draft").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", draft.path("contentVersion").asLong(),
            "expectedRevisionVersion", draft.path("revisionVersion").asLong(), "title", "Synthetic second revision",
            "bodyMarkdown", "Second revision body for the change note check.", "assets", List.of(),
            "changeNote", "Fix the timeout value"))), "000005");
        assertThat(saved.path("changeNote").asText()).isEqualTo("Fix the timeout value");
        assertThat(saved.path("editor").path("userId").asText()).isEqualTo("000005");
        JsonNode mine = success(get(API + "/me/contents").param("kind", "article").param("size", "100"), "000005");
        assertThat(mine.path("items").findParents("contentId").stream()
            .filter(item -> item.path("contentId").asLong() == id).findFirst().orElseThrow()
            .path("hasDraft").asBoolean()).isTrue();

        moderator("000006");
        success(post(API + "/reports").content(json.writeValueAsString(Map.of(
            "contentId", id, "reasonCode", "outdated"))), "000004");
        JsonNode queue = success(get(API + "/moderation/reports").param("size", "100"), "000006");
        JsonNode target = queue.path("items").findParents("target").stream()
            .map(item -> item.path("target")).filter(value -> value.path("contentId").asLong() == id)
            .findFirst().orElseThrow();
        assertThat(target.path("kind").asText()).isEqualTo("article");
        assertThat(target.path("title").asText()).isEqualTo("Synthetic test article");

        String org = jdbc.queryForObject("select org_id::text from learning_hub.users where user_id = '000006'",
            String.class);
        assertThat(success(get(API + "/moderation/candidates").param("orgId", org), "000003")
            .path("items").findValuesAsText("userId")).contains("000004", "000006");
        assertThat(success(get(API + "/moderation/candidates").param("orgId", org), "000006")
            .path("totalElements").asLong()).isPositive();
        mvc.perform(get(API + "/moderation/candidates").param("orgId", org)
            .with(jwt().jwt(token -> token.subject("000004")))).andExpect(status().isForbidden());
    }

    @Test
    void simultaneousRetriesCreateExactlyOneContentAndVote() throws Exception {
        String body = json.writeValueAsString(body("organization", List.of()));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> success(post(API + "/articles").content(body), "000005"));
            var second = executor.submit(() -> success(post(API + "/articles").content(body), "000005"));
            JsonNode article = first.get(30, TimeUnit.SECONDS);
            assertThat(second.get(30, TimeUnit.SECONDS).path("contentId")).isEqualTo(article.path("contentId"));
            long id = article.path("contentId").asLong();
            publish(id, article, "000005");
            var vote1 = executor.submit(() -> success(put(API + "/contents/" + id + "/vote")
                .content("{\"value\":1}"), "000004"));
            var vote2 = executor.submit(() -> success(put(API + "/contents/" + id + "/vote")
                .content("{\"value\":1}"), "000004"));
            assertThat(vote1.get(30, TimeUnit.SECONDS).path("score").asLong()).isEqualTo(1);
            assertThat(vote2.get(30, TimeUnit.SECONDS).path("score").asLong()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from learning_hub.community_votes where content_id = ?",
                Long.class, id)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void editorCannotPublishOrOpenAccessAndRevocationAppliesImmediately() throws Exception {
        JsonNode article = create("000005", "articles", "restricted", List.of());
        long id = article.path("contentId").asLong();
        JsonNode access = success(put(API + "/contents/" + id + "/access").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", article.path("contentVersion").asLong(), "visibility", "restricted",
            "grants", List.of(Map.of("userId", "000004", "permission", "editor"))))), "000005");
        article = success(get(API + "/contents/" + id + "?includeOwnDraft=true"), "000004");
        mvc.perform(post(API + "/contents/" + id + "/publish").with(jwt().jwt(token -> token.subject("000004")))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(versions(article))))
            .andExpect(status().isForbidden());
        success(put(API + "/contents/" + id + "/access").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", access.path("contentVersion").asLong(), "visibility", "restricted",
            "grants", List.of()))), "000005");
        assertThat(response(get(API + "/contents/" + id + "?includeOwnDraft=true"), "000004")
            .path("messageCode").asText()).isEqualTo("CORE-RESOURCE_NOT_FOUND");
    }

    @Test
    void gzipCannotBypassJsonTransportLimit() throws Exception {
        byte[] body = ("{\"title\":\"" + "x".repeat(8 * 1024 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(body);
        }
        mvc.perform(post(API + "/articles").with(jwt().jwt(token -> token.subject("000005")))
            .contentType(MediaType.APPLICATION_JSON).header("Content-Encoding", "gzip")
            .content(compressed.toByteArray())).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void markdownRejectsMultilineHtmlAndEncodedSchemesButKeepsActualCode() throws Exception {
        for (String unsafe : List.of("<script\n src='example'>bad</script>",
            "[link](jav&#x61;script:alert(1))", "![image](//example.invalid/private.png)",
            "![image][remote]\n[remote]: https://example.invalid/image.png", "`<script>bad</script>``")) {
            var body = body("organization", List.of());
            body.put("bodyMarkdown", unsafe);
            assertThat(response(post(API + "/articles").content(json.writeValueAsString(body)), "000005")
                .path("status").asText()).isEqualTo("ERROR");
        }
        var safe = body("organization", List.of());
        safe.put("bodyMarkdown", "A code sample follows.\n```html\n<script>text only</script>\n```\n"
            + "Inline: `<script>escaped text</script>`.");
        success(post(API + "/articles").content(json.writeValueAsString(safe)), "000005");
    }

    @Test
    void preferenceScheduleExpertiseAndSubscriptionsUseCurrentRights() throws Exception {
        JsonNode preference = success(get(API + "/me/preferences"), "000004");
        preference = success(put(API + "/me/preferences").content(json.writeValueAsString(Map.of(
            "expectedVersion", preference.path("version").asLong(), "timeZone", "Asia/Ho_Chi_Minh",
            "digestFrequency", "weekly", "digestLocalTime", "08:00:00", "digestWeekday", 1,
            "notificationPreferences", Map.of("subscription", true)))), "000004");
        assertThat(preference.path("nextDigestAt").isTextual()).isTrue();
        Long tag = jdbc.queryForObject("select max(tag_id) from learning_hub.community_tags", Long.class);
        success(put(API + "/me/subscriptions").content(json.writeValueAsString(
            Map.of("tagId", tag, "mode", "digest"))), "000004");
        assertThat(success(get(API + "/me/subscriptions"), "000004").path("totalElements").asLong()).isPositive();
        success(put(API + "/me/expertise").content(json.writeValueAsString(Map.of(
            "expectedPreferenceVersion", preference.path("version").asLong(), "items", List.of(Map.of(
                "tagId", tag, "description", "Synthetic skill", "availableForHelp", true))))), "000004");
        JsonNode experts = success(get(API + "/experts?tagIds=" + tag + "&available=true"), "000005");
        assertThat(experts.path("totalElements").asLong()).isPositive();
        assertThat(experts.toString()).doesNotContain("email", "password", "auth_ver");
    }

    @Test
    void sharedCollectionNeverPublishesPrivateItemsOrNotes() throws Exception {
        JsonNode publicArticle = create("000005", "articles", "organization", List.of());
        long publicId = publicArticle.path("contentId").asLong();
        publish(publicId, publicArticle, "000005");
        JsonNode privateArticle = create("000005", "articles", "restricted", List.of());
        long privateId = privateArticle.path("contentId").asLong();
        publish(privateId, privateArticle, "000005");
        JsonNode collection = success(post(API + "/collections").content(json.writeValueAsString(Map.of(
            "clientRequestId", UUID.randomUUID(), "name", "Synthetic collection", "visibility", "organization",
            "grants", List.of()))), "000005");
        long id = collection.path("collectionId").asLong();
        JsonNode saved = success(put(API + "/collections/" + id + "/items/" + publicId)
            .content(json.writeValueAsString(Map.of("expectedCollectionVersion", collection.path("version").asLong(),
                "saved", true, "note", "Public note", "sortNo", 1))), "000005");
        success(put(API + "/collections/" + id + "/items/" + privateId).content(json.writeValueAsString(Map.of(
            "expectedCollectionVersion", saved.path("collectionVersion").asLong(), "saved", true,
            "note", "Private sentinel never exposed", "sortNo", 0))), "000005");
        JsonNode reader = success(get(API + "/collections/" + id + "/items?size=1"), "000004");
        assertThat(reader.path("totalElements").asLong()).isEqualTo(1);
        assertThat(reader.path("collection").path("visibleItemCount").asLong()).isEqualTo(1);
        assertThat(reader.toString()).doesNotContain("Private sentinel never exposed");
        assertThat(success(get(API + "/collections/" + id + "/items"), "000005")
            .path("totalElements").asLong()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            success(put(API + "/me/bookmarks/" + publicId).content("{\"saved\":true}"), "000004");
        }
        assertThat(success(get(API + "/me/bookmarks"), "000004").path("items").isArray()).isTrue();
    }

    @Test
    void privateWikiApprovalDoesNotOpenOtherPrivateRevisionsAndPreventsSelfApproval() throws Exception {
        moderator("000005");
        moderator("000006");
        JsonNode guild = success(post(API + "/guilds").content(json.writeValueAsString(Map.of(
            "clientRequestId", UUID.randomUUID(), "slug", "wiki-" + UUID.randomUUID(), "name", "Wiki test guild",
            "visibility", "private", "joinPolicy", "invite"))), "000005");
        var body = body("restricted", List.of());
        body.put("slug", "standard-" + UUID.randomUUID());
        body.put("sortNo", 0);
        body.put("context", Map.of("wiki", Map.of("documentClass", "standard", "approvalScope", "Synthetic Guild")));
        JsonNode wiki = success(post(API + "/guilds/" + guild.path("guildId").asLong() + "/wiki/pages")
            .content(json.writeValueAsString(body)), "000005");
        long id = wiki.path("contentId").asLong();
        JsonNode submitted = success(post(API + "/contents/" + id + "/submit")
            .content(json.writeValueAsString(versions(wiki))), "000005");
        long revision = submitted.path("revisionId").asLong();
        var decision = Map.of("expectedContentVersion", submitted.path("contentVersion").asLong(),
            "expectedRevisionVersion", submitted.path("revisionVersion").asLong(), "decision", "approved");
        assertThat(response(post(API + "/revisions/" + revision + "/decisions")
            .content(json.writeValueAsString(decision)), "000005").path("status").asText()).isEqualTo("ERROR");
        success(get(API + "/contents/" + id + "/revisions/" + revision), "000006");
        success(post(API + "/revisions/" + revision + "/decisions").content(json.writeValueAsString(decision)), "000006");
        assertThat(response(get(API + "/contents/" + id), "000006").path("messageCode").asText())
            .isEqualTo("CORE-RESOURCE_NOT_FOUND");
    }

    @Test
    void privateEvidenceRequiresAssignedCaseAndRestoreUsesLatestAction() throws Exception {
        moderator("000006");
        JsonNode article = create("000005", "articles", "restricted", List.of());
        long content = article.path("contentId").asLong();
        publish(content, article, "000005");
        JsonNode report = success(post(API + "/reports").content(json.writeValueAsString(Map.of(
            "contentId", content, "reasonCode", "outdated", "description", "Synthetic review case"))), "000005");
        long id = report.path("reportId").asLong();
        mvc.perform(get(API + "/moderation/reports/" + id + "/evidence")
            .with(jwt().jwt(token -> token.subject("000006")))).andExpect(status().isForbidden());
        report = success(post(API + "/moderation/reports/" + id + "/assign").content(json.writeValueAsString(Map.of(
            "expectedVersion", report.path("version").asLong(), "moderatorUserId", "000006"))), "000006");
        JsonNode evidence = success(get(API + "/moderation/reports/" + id + "/evidence"), "000006");
        report = success(post(API + "/moderation/reports/" + id + "/actions").content(json.writeValueAsString(Map.of(
            "expectedReportVersion", report.path("version").asLong(), "expectedTargetVersion", evidence.path("targetVersion").asLong(),
            "action", "hide", "reason", "Synthetic hide operation"))), "000006");
        assertThat(response(get(API + "/contents/" + content), "000005").path("status").asText()).isEqualTo("ERROR");
        Long outcomeEvent = jdbc.queryForObject("select max(event_id) from learning_hub.community_outbox_events "
            + "where content_id=? and event_type='content_moderated'", Long.class, content);
        new TransactionTemplate(transactions).execute(status -> {
            writeLock.acquire();
            var event = community.require(CommunityOutboxEvent.class, outcomeEvent);
            fanout.deliver(event, fanout.audience(event, "000006"), "", "000006");
            return null;
        });
        Long notification = jdbc.queryForObject("select notification_id from learning_hub.community_notifications "
            + "where event_id=? and recipient_user_id='000005'", Long.class, outcomeEvent);
        JsonNode outcome = success(post(API + "/me/notifications/" + notification + "/read"), "000005");
        assertThat(outcome.path("content").isNull()).isTrue();
        assertThat(outcome.path("message").asText()).contains("điều phối");
        long restoreAction = report.path("actions").get(0).path("actionId").asLong();
        report = success(post(API + "/reports").content(json.writeValueAsString(Map.of(
            "contentId", content, "reasonCode", "restore", "description", "Synthetic restore case"))), "000005");
        id = report.path("reportId").asLong();
        report = success(post(API + "/moderation/reports/" + id + "/assign").content(json.writeValueAsString(Map.of(
            "expectedVersion", report.path("version").asLong(), "moderatorUserId", "000006"))), "000006");
        evidence = success(get(API + "/moderation/reports/" + id + "/evidence"), "000006");
        success(post(API + "/moderation/reports/" + id + "/actions").content(json.writeValueAsString(Map.of(
            "expectedReportVersion", report.path("version").asLong(), "expectedTargetVersion", evidence.path("targetVersion").asLong(),
            "action", "restore", "restoreActionId", restoreAction, "reason", "Synthetic restore operation"))), "000006");
        success(get(API + "/contents/" + content), "000005");
    }

    private void moderator(String userId) throws Exception {
        Long org = jdbc.queryForObject("select org_id from learning_hub.users where user_id = ?", Long.class, userId);
        success(put(API + "/moderation/moderators/" + userId).content(json.writeValueAsString(Map.of(
            "orgId", org, "active", true, "expectedVersion", 0, "reason", "Synthetic API test fixture"))), "000003");
    }

    @Test
    void transcriptResponseVersionMatchesCommittedRowForIntegerAndFractionalSegments() throws Exception {
        // Fixture metadata chỉ kiểm persistence/version transcript; kiểm file/S3 thật nằm ở bộ HTTP media riêng.
        Long assetId = jdbc.queryForObject("""
            insert into learning_hub.community_assets
              (owner_org_id,uploaded_by,original_name,mime_type,size_bytes,media_type,status,duration_seconds,
               upload_id,request_hash,upload_total_bytes,created_by,updated_by)
            select org_id,'000005','transcript-version-fixture.mp4','video/mp4',1,'video','ready',5,
              gen_random_uuid(),repeat('0',64),1,'000005','000005'
            from learning_hub.users where user_id='000005' returning asset_id
            """, Long.class);
        JsonNode transcript = success(post(API + "/assets/" + assetId + "/transcripts").content(
            json.writeValueAsString(Map.of("clientRequestId", UUID.randomUUID(), "language", "vi",
                "bodyText", "Synthetic transcript.", "segments", List.of(Map.of(
                    "startSeconds", 0, "endSeconds", 1, "text", "Synthetic transcript."))))), "000005");
        long id = transcript.path("transcriptId").asLong();
        assertThat(jdbc.queryForObject("select row_version from learning_hub.community_media_transcripts "
            + "where transcript_id=?", Long.class, id)).isEqualTo(transcript.path("version").asLong());
        transcript = success(put(API + "/transcripts/" + id).content(json.writeValueAsString(Map.of(
            "expectedVersion", transcript.path("version").asLong(), "language", "vi", "bodyText", "Revised transcript.",
            "segments", List.of(Map.of("startSeconds", 0.25, "endSeconds", 1.25, "text", "Revised transcript."))))),
            "000005");
        assertThat(jdbc.queryForObject("select row_version from learning_hub.community_media_transcripts "
            + "where transcript_id=?", Long.class, id)).isEqualTo(transcript.path("version").asLong());
        transcript = success(post(API + "/transcripts/" + id + "/publish").content(json.writeValueAsString(
            Map.of("expectedVersion", transcript.path("version").asLong()))), "000005");
        assertThat(transcript.path("status").asText()).isEqualTo("published");
        assertThat(jdbc.queryForObject("select row_version from learning_hub.community_media_transcripts "
            + "where transcript_id=?", Long.class, id)).isEqualTo(transcript.path("version").asLong());
    }

    @Test
    @EnabledIfSystemProperty(named = "community.vector.integration", matches = "true")
    void vectorTopKAppliesAclBeforeLimitAndResolveRechecksRevocation() throws Exception {
        JsonNode guild = success(post(API + "/guilds").content(json.writeValueAsString(Map.of(
            "clientRequestId", UUID.randomUUID(), "slug", "vector-" + UUID.randomUUID(),
            "name", "Synthetic vector scope", "visibility", "organization", "joinPolicy", "open"))), "000005");
        long guildId = guild.path("guildId").asLong();
        long[] ids = new long[2];
        JsonNode publicContent = null;
        for (int index = 0; index < 2; index++) {
            var input = body(index == 0 ? "restricted" : "organization", List.of());
            JsonNode content = success(post(API + "/articles").content(json.writeValueAsString(input)), "000005");
            ids[index] = content.path("contentId").asLong();
            content = publish(ids[index], content, "000005");
            if (index == 1) {
                publicContent = content;
            }
            jdbc.update("""
                insert into learning_hub.community_content_guilds
                  (content_id,guild_id,is_pinned,created_by,updated_by) values (?,?,false,'000005','000005')
                """, ids[index], guildId);
            String vector = "[" + (index == 0 ? "1,0" : "0.8,0.2") + ",0".repeat(1022) + "]";
            jdbc.update("""
                insert into learning_hub.community_search_chunks
                  (content_id,revision_id,chunk_no,body_text,embedding,embedding_model,index_status,created_by,updated_by)
                values (?,?,0,'Synthetic vector ACL fixture',cast(? as vector),'bge-m3','ready','000005','000005')
                """, ids[index], content.path("publishedRevision").path("revisionId").asLong(), vector);
        }
        var originalContext = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("synthetic")
            .header("alg", "none").subject("000004").build(), List.of()));
        try {
            SecurityContextHolder.setContext(context);
            float[] query = new float[1024];
            query[0] = 1;
            var hits = knowledgeSource.retrieve(query, "synthetic", guildId, 1, "bge-m3");
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).pointer().sourceId()).isEqualTo(ids[1]);
            SecurityContextHolder.setContext(originalContext);
            success(put(API + "/contents/" + ids[1] + "/access").content(json.writeValueAsString(Map.of(
                "expectedContentVersion", publicContent.path("contentVersion").asLong(),
                "visibility", "restricted", "grants", List.of()))), "000005");
            SecurityContextHolder.setContext(context);
            assertThat(knowledgeSource.resolve(hits.get(0).pointer())).isNull();
            assertThat(knowledgeSource.retrieve(query, "synthetic", guildId, 1, "bge-m3")).isEmpty();
        } finally {
            SecurityContextHolder.setContext(originalContext);
        }
    }

    @Test
    void pausedAiEndpointsAreNotRegisteredAndCannotCreateTurns() throws Exception {
        var request = Map.of("requestId", UUID.randomUUID(), "question", "Synthetic AI question",
            "sourceTypes", List.of("community"));
        Long before = jdbc.queryForObject("select count(*) from learning_hub.community_ai_turns", Long.class);
        mvc.perform(post(API + "/ai/answers").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request)).with(jwt().jwt(token -> token.subject("000005"))))
            .andExpect(status().isNotFound());
        mvc.perform(get(API + "/ai/turns/1/sources").with(jwt().jwt(token -> token.subject("000005"))))
            .andExpect(status().isNotFound());
        mvc.perform(put(API + "/ai/turns/1/feedback").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":0,\"helpful\":false}")
            .with(jwt().jwt(token -> token.subject("000005")))).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.community_ai_turns", Long.class))
            .isEqualTo(before);
    }

    @Test
    void assistantReadToolDiscardsLinksAfterSourceAclChanges() throws Exception {
        var input = body("organization", List.of());
        String title = "assistant" + UUID.randomUUID().toString().replace("-", "");
        input.put("title", title);
        JsonNode article = success(post(API + "/articles").content(json.writeValueAsString(input)), "000005");
        long id = article.path("contentId").asLong();
        article = publish(id, article, "000005");
        TurnState turn = new TurnState();
        communityTool.run(json.valueToTree(Map.of("query", title)), new AgentContext("000004", null, null), turn);
        assertThat(turn.finisher().orElseThrow().finish("Model cannot replace server links").answer())
            .contains("/community/contents/" + id);
        success(put(API + "/contents/" + id + "/access").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", article.path("contentVersion").asLong(), "visibility", "restricted",
            "grants", List.of()))), "000005");
        assertThat(turn.finisher().orElseThrow().finish(title).answer()).doesNotContain(title, "/community/contents/");
    }

    @Test
    void digestClaimsAreIdempotentAndRecheckSourceRightsBeforeDelivery() throws Exception {
        JsonNode preference = success(get(API + "/me/preferences"), "000004");
        success(put(API + "/me/preferences").content(json.writeValueAsString(Map.of(
            "expectedVersion", preference.path("version").asLong(), "timeZone", "Asia/Ho_Chi_Minh",
            "digestFrequency", "daily", "digestLocalTime", "08:00:00",
            "notificationPreferences", Map.of("subscription", true)))), "000004");
        JsonNode article = create("000005", "articles", "organization", List.of());
        long id = article.path("contentId").asLong();
        article = publish(id, article, "000005");
        Long eventId = jdbc.queryForObject("select max(event_id) from learning_hub.community_outbox_events "
            + "where content_id=? and event_type='content_published'", Long.class, id);
        Long notificationId = jdbc.queryForObject("""
            insert into learning_hub.community_notifications
              (event_id,recipient_user_id,content_id,delivery_mode,delivery_status,created_by,updated_by)
            values (?,'000004',?,'digest','pending','000005','000005') returning notification_id
            """, Long.class, eventId, id);
        success(put(API + "/contents/" + id + "/access").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", article.path("contentVersion").asLong(), "visibility", "restricted",
            "grants", List.of()))), "000005");
        new TransactionTemplate(transactions).execute(status -> {
            writeLock.acquire();
            NotificationDigestBatch batch = new NotificationDigestBatch();
            batch.setUserId("000004");
            batch.setWindowStart(Instant.EPOCH);
            batch.setWindowEnd(Instant.now().plusSeconds(1));
            batch.setBatchStatus("pending");
            batch.setAttemptCount(0);
            community.save(batch, "000005");
            fanout.claimDigestItems(batch, "000005");
            community.flush();
            fanout.claimDigestItems(batch, "000005");
            return null;
        });
        assertThat(jdbc.queryForObject("select delivery_status from learning_hub.community_notifications "
            + "where notification_id=?", String.class, notificationId)).isEqualTo("canceled");
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.community_notification_digest_items "
            + "where notification_id=?", Long.class, notificationId)).isEqualTo(1L);
    }

    @Test
    void fanoutAndContributionLedgerAreIdempotentAndReverseAfterAclChange() throws Exception {
        moderator("000006");
        JsonNode article = create("000005", "articles", "organization", List.of());
        long id = article.path("contentId").asLong();
        success(put(API + "/me/subscriptions").content(json.writeValueAsString(Map.of("authorUserId", "000005",
            "mode", "instant"))), "000004");
        article = publish(id, article, "000005");
        Long org = jdbc.queryForObject("select owner_org_id from learning_hub.community_contents where content_id=?",
            Long.class, id);
        Long eventId = jdbc.queryForObject("select max(event_id) from learning_hub.community_outbox_events "
            + "where content_id=? and event_type='content_published'", Long.class, id);
        new TransactionTemplate(transactions).execute(status -> {
            writeLock.acquire();
            var event = community.require(CommunityOutboxEvent.class, eventId);
            var audience = fanout.audience(event, "000005");
            fanout.deliver(event, audience, "", "000005");
            fanout.deliver(event, audience, "", "000005");
            return null;
        });
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.community_notifications "
            + "where event_id=? and recipient_user_id=?", Long.class, eventId, "000004")).isEqualTo(1L);
        success(put(API + "/contents/" + id + "/vote").content("{\"value\":1}"), "000004");
        JsonNode badge = success(post(API + "/badges").content(json.writeValueAsString(Map.of(
            "code", "TEST_" + UUID.randomUUID().toString().replace("-", ""), "name", "Synthetic upvote badge",
            "ruleVersion", 1, "rule", Map.of("metric", "received_upvotes", "threshold", 1)))), "000006");
        new TransactionTemplate(transactions).execute(status -> {
            writeLock.acquire();
            contributionRepository.reconcile(org, id, eventId, "000005");
            contributionRepository.reconcile(org, id, eventId, "000005");
            badgeRepository.reconcileAwards(org, eventId, "000005");
            return null;
        });
        assertThat(jdbc.queryForObject("select sum(points_delta) from learning_hub.community_contribution_events "
            + "where content_id=?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.community_badge_awards "
            + "where badge_id=? and user_id=? and status='active'", Long.class, badge.path("badgeId").asLong(), "000005"))
            .isEqualTo(1L);
        success(put(API + "/contents/" + id + "/access").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", article.path("contentVersion").asLong(), "visibility", "restricted",
            "grants", List.of()))), "000005");
        new TransactionTemplate(transactions).execute(status -> {
            writeLock.acquire();
            contributionRepository.reconcile(org, id, eventId, "000005");
            contributionRepository.reconcile(org, id, eventId, "000005");
            badgeRepository.reconcileAwards(org, eventId, "000005");
            return null;
        });
        assertThat(jdbc.queryForObject("select sum(points_delta) from learning_hub.community_contribution_events "
            + "where content_id=?", Long.class, id)).isZero();
        assertThat(success(get(API + "/me/notifications"), "000004").toString()).doesNotContain("\"contentId\":" + id + ",");
    }

    @Test
    void unifiedSearchAndLinksKeepPrivateTargetsOutOfPageCounts() throws Exception {
        String token = "search" + UUID.randomUUID().toString().replace("-", "");
        var input = body("organization", List.of());
        input.put("title", token);
        JsonNode source = success(post(API + "/articles").content(json.writeValueAsString(input)), "000005");
        long id = source.path("contentId").asLong();
        source = publish(id, source, "000005");
        JsonNode hidden = create("000005", "articles", "restricted", List.of());
        long privateId = hidden.path("contentId").asLong();
        publish(privateId, hidden, "000005");
        JsonNode visible = create("000005", "articles", "organization", List.of());
        long publicId = visible.path("contentId").asLong();
        publish(publicId, visible, "000005");
        success(put(API + "/contents/" + id + "/links").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", source.path("contentVersion").asLong(), "links", List.of(
                Map.of("relationType", "related", "targetContentId", privateId),
                Map.of("relationType", "related", "targetContentId", publicId))))), "000005");
        JsonNode result = success(get(API + "/contents/" + id + "/links"), "000004");
        assertThat(result.path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("targetId").asLong()).isEqualTo(publicId);
        result = success(get(API + "/search?q=" + token), "000004");
        assertThat(result.path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("sourceId").asLong()).isEqualTo(id);
    }

    @Test
    void contributionScoreDropsImmediatelyWhenPublicSourceBecomesPrivate() throws Exception {
        JsonNode article = create("000005", "articles", "organization", List.of());
        long id = article.path("contentId").asLong();
        article = publish(id, article, "000005");
        JsonNode before = success(get(API + "/me/contributions"), "000005");
        long baseline = before.path("eligibleScore").asLong();
        success(put(API + "/contents/" + id + "/vote").content("{\"value\":1}"), "000004");
        assertThat(success(get(API + "/me/contributions"), "000005").path("eligibleScore").asLong())
            .isEqualTo(baseline + 1);
        success(put(API + "/contents/" + id + "/access").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", article.path("contentVersion").asLong(), "visibility", "restricted",
            "grants", List.of()))), "000005");
        assertThat(success(get(API + "/me/contributions"), "000005").path("eligibleScore").asLong()).isEqualTo(baseline);
        success(get(API + "/leaderboard"), "000004");
        success(get(API + "/badges"), "000004");
        success(get(API + "/users/000005/badges"), "000004");
    }

    @Test
    void analyticsCountsUtcViewsOnceAndEmptyPeriodHasNullRatio() throws Exception {
        JsonNode article = create("000005", "articles", "organization", List.of());
        long content = article.path("contentId").asLong();
        publish(content, article, "000005");
        success(post(API + "/contents/" + content + "/view"), "000004");
        success(post(API + "/contents/" + content + "/view"), "000004");
        assertThat(jdbc.queryForObject("select count(*) from learning_hub.community_content_views "
            + "where content_id = ? and user_id = ?", Long.class, content, "000004")).isEqualTo(1L);
        JsonNode overview = success(get(API + "/analytics/overview?from=2099-01-01T00:00:00Z&to=2099-01-02T00:00:00Z"),
            "000004");
        assertThat(overview.path("questions").asLong()).isZero();
        assertThat(overview.path("solvedRatio").isNull()).isTrue();
        assertThat(success(get(API + "/analytics/activity?from=2099-01-01T00:00:00Z&to=2099-01-03T00:00:00Z"),
            "000004").size()).isEqualTo(2);
        success(get(API + "/me/notifications"), "000004");
        success(get(API + "/me/digests"), "000004");
        success(get(API + "/me/assets"), "000004");
    }

    @Test
    void architectureReviewPinsThreadsAndConcludesOnlyAfterResolution() throws Exception {
        var incomplete = body("organization", List.of());
        incomplete.put("context", Map.of("review", Map.of("problem", "", "constraints", "", "decisionsNeeded", List.of())));
        JsonNode incompleteDraft = success(post(API + "/reviews").content(json.writeValueAsString(incomplete)), "000005");
        long incompleteId = incompleteDraft.path("contentId").asLong();
        assertThat(response(post(API + "/contents/" + incompleteId + "/publish")
            .content(json.writeValueAsString(versions(incompleteDraft))), "000005").path("status").asText())
            .isEqualTo("ERROR");
        var body = body("organization", List.of());
        body.put("context", Map.of("review", Map.of("problem", "Synthetic architecture design problem",
            "constraints", "Synthetic constraints", "decisionsNeeded", List.of("Choose safe storage"))));
        JsonNode created = success(post(API + "/reviews").content(json.writeValueAsString(body)), "000005");
        long content = created.path("contentId").asLong();
        assertThat(success(get(API + "/reviews/" + content), "000005").path("draftRevision").isNull()).isFalse();
        assertThat(response(get(API + "/reviews/" + content), "000004").path("status").asText()).isEqualTo("ERROR");
        assertThat(response(get(API + "/reviews/" + content + "/invitations"), "000004")
            .path("status").asText()).isEqualTo("ERROR");
        JsonNode published = publish(content, created, "000005");
        published = success(post(API + "/contents/" + content + "/lifecycle").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", published.path("contentVersion").asLong(), "action", "open_review",
            "reason", "Synthetic open review"))), "000005");
        long revision = published.path("publishedRevision").path("revisionId").asLong();
        JsonNode detail = success(get(API + "/reviews/" + content), "000005");
        assertThat(detail.path("reviewCapabilities").toString()).contains("invite", "conclude");
        assertThat(success(get(API + "/reviews/" + content), "000004")
            .path("reviewCapabilities").toString()).doesNotContain("invite", "conclude");
        JsonNode invited = success(post(API + "/reviews/" + content + "/invitations")
            .content(json.writeValueAsString(Map.of("reviewerUserIds", List.of("000004")))), "000005");
        assertThat(invited.get(0).path("capabilities").toString()).contains("cancel");
        assertThat(success(get(API + "/reviews/" + content + "/invitations"), "000004")
            .path("items").get(0).path("capabilities").toString()).contains("accept", "decline");
        var request = Map.of("clientRequestId", UUID.randomUUID(), "revisionId", revision,
            "anchor", Map.of("type", "section", "section", "Storage design"),
            "bodyMarkdown", "Synthetic feedback on storage design");
        JsonNode thread = success(post(API + "/reviews/" + content + "/threads")
            .content(json.writeValueAsString(request)), "000004");
        assertThat(success(post(API + "/reviews/" + content + "/threads")
            .content(json.writeValueAsString(request)), "000004").path("threadId").asLong())
            .isEqualTo(thread.path("threadId").asLong());
        assertThat(success(get(API + "/reviews/" + content + "/threads?revisionId=" + revision), "000004")
            .path("totalElements").asLong()).isEqualTo(1);
        assertThat(success(get(API + "/reviews/" + content), "000005")
            .path("reviewCapabilities").toString()).doesNotContain("conclude");
        assertThat(response(get(API + "/reviews/" + content + "/threads?revisionId=" + Long.MAX_VALUE), "000004")
            .path("status").asText()).isEqualTo("ERROR");
        var conclusion = Map.of("expectedContentVersion", published.path("contentVersion").asLong(),
            "concludedRevisionId", revision, "conclusion", "Synthetic independent review conclusion");
        assertThat(response(post(API + "/reviews/" + content + "/conclude")
            .content(json.writeValueAsString(conclusion)), "000005").path("status").asText()).isEqualTo("ERROR");
        success(put(API + "/review-threads/" + thread.path("threadId").asLong()).content(json.writeValueAsString(Map.of(
            "expectedVersion", thread.path("version").asLong(), "action", "resolve", "note", "Synthetic resolved"))),
            "000004");
        JsonNode concluded = success(post(API + "/reviews/" + content + "/conclude")
            .content(json.writeValueAsString(conclusion)), "000005");
        assertThat(concluded.path("reviewStatus").asText()).isEqualTo("concluded");
        assertThat(response(post(API + "/contents/" + content + "/comments").content(json.writeValueAsString(Map.of(
            "clientRequestId", UUID.randomUUID(), "bodyMarkdown", "Comment after conclusion"))), "000004")
            .path("status").asText()).isEqualTo("ERROR");
        assertThat(success(get(API + "/reviews?status=concluded"), "000004").toString()).contains("concluded");
    }

    @Test
    void requestFulfillmentRequiresFullAudienceAndCanReopenClosedRequest() throws Exception {
        JsonNode article = create("000005", "articles", "restricted", List.of());
        long target = article.path("contentId").asLong();
        publish(target, article, "000005");
        JsonNode request = create("000005", "knowledge-requests", "organization", List.of());
        long id = request.path("contentId").asLong();
        request = publish(id, request, "000005");
        assertThat(response(post(API + "/knowledge-requests/" + id + "/actions")
            .content(json.writeValueAsString(Map.of("expectedContentVersion", request.path("contentVersion").asLong(),
                "action", "fulfill", "fulfillmentContentIds", List.of(target)))), "000005")
            .path("status").asText()).isEqualTo("ERROR");
        request = success(post(API + "/knowledge-requests/" + id + "/actions").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", request.path("contentVersion").asLong(), "action", "close",
            "reason", "Synthetic close request"))), "000005");
        request = success(post(API + "/knowledge-requests/" + id + "/actions").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", request.path("contentVersion").asLong(), "action", "reopen",
            "reason", "Synthetic reopen request"))), "000005");
        assertThat(request.path("requestStatus").asText()).isEqualTo("open");
        success(get(API + "/knowledge-requests?status=open"), "000005");
    }

    @Test
    void swaggerDeclaresSuccessResponseSchemaForEveryImplementedRoute() throws Exception {
        JsonNode doc = json.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        Files.createDirectories(Path.of("tmp"));
        Files.writeString(Path.of("tmp/community-openapi.json"), doc.toPrettyString(), StandardCharsets.UTF_8);
        AtomicInteger routeCount = new AtomicInteger();
        doc.path("paths").fields().forEachRemaining(path -> {
            if (path.getKey().startsWith(API)) {
                path.getValue().fields().forEachRemaining(operation -> {
                    routeCount.incrementAndGet();
                    JsonNode ok = operation.getValue().path("responses").path("200");
                    assertThat(ok.isMissingNode()).as(path.getKey()).isFalse();
                    if (!"head".equals(operation.getKey())) {
                        boolean jsonSchema = !ok.path("content").path("application/json").path("schema").isMissingNode();
                        boolean binarySchema = !ok.path("content").path("application/octet-stream").path("schema").isMissingNode();
                        assertThat(jsonSchema || binarySchema).as(path.getKey()).isTrue();
                    }
                });
            }
        });
        assertThat(routeCount.get()).isEqualTo(141);
        doc.path("paths").fieldNames().forEachRemaining(path ->
            assertThat(path).doesNotStartWith(API + "/ai/"));
    }

    @Test
    void searchAndLinksNeverExposePrivateTargetOrWrongTotal() throws Exception {
        String token = "search" + UUID.randomUUID().toString().replace("-", "");
        var input = body("organization", List.of());
        input.put("title", token);
        JsonNode source = success(post(API + "/articles").content(json.writeValueAsString(input)), "000005");
        long id = source.path("contentId").asLong();
        source = publish(id, source, "000005");
        JsonNode target = create("000005", "articles", "restricted", List.of());
        long privateId = target.path("contentId").asLong();
        publish(privateId, target, "000005");
        success(put(API + "/contents/" + id + "/links").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", source.path("contentVersion").asLong(), "links", List.of(
                Map.of("relationType", "related", "targetContentId", privateId))))), "000005");
        assertThat(success(get(API + "/contents/" + id + "/links"), "000004")
            .path("totalElements").asLong()).isZero();
        var result = success(get(API + "/search?q=" + token), "000004");
        assertThat(result.path("totalElements").asLong()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("sourceId").asLong()).isEqualTo(id);
    }

    @Test
    void contributionReadsUseCurrentPublicEligibility() throws Exception {
        JsonNode article = create("000005", "articles", "organization", List.of());
        long id = article.path("contentId").asLong();
        article = publish(id, article, "000005");
        long baseline = success(get(API + "/me/contributions"), "000005").path("eligibleScore").asLong();
        success(put(API + "/contents/" + id + "/vote").content("{\"value\":1}"), "000004");
        assertThat(success(get(API + "/me/contributions"), "000005").path("eligibleScore").asLong())
            .isEqualTo(baseline + 1);
        success(put(API + "/contents/" + id + "/access").content(json.writeValueAsString(Map.of(
            "expectedContentVersion", article.path("contentVersion").asLong(), "visibility", "restricted",
            "grants", List.of()))), "000005");
        assertThat(success(get(API + "/me/contributions"), "000005").path("eligibleScore").asLong()).isEqualTo(baseline);
        success(get(API + "/leaderboard"), "000004");
        success(get(API + "/badges"), "000004");
        success(get(API + "/users/000005/badges"), "000004");
    }

    @Test
    void articleAndQuestionCardsUsePublishedCoversAndRejectInvalidCovers() throws Exception {
        Long assetId = jdbc.queryForObject("""
            insert into learning_hub.community_assets
              (owner_org_id,uploaded_by,original_name,mime_type,size_bytes,media_type,status,media_checked_at,
               upload_id,request_hash,upload_total_bytes,created_by,updated_by)
            select org_id,'000005','cover.webp','image/webp',1,'image','ready',now(),
              gen_random_uuid(),repeat('0',64),1,'000005','000005'
            from learning_hub.users where user_id='000005' returning asset_id
            """, Long.class);
        var command = body("organization", List.of());
        command.put("assets", List.of(Map.of("assetId", assetId, "usage", "cover", "sortNo", 0)));
        JsonNode created = success(post(API + "/articles").content(json.writeValueAsString(command)), "000005");
        long id = created.path("contentId").asLong();
        long cover = created.path("draftRevision").path("assets").get(0).path("revisionAssetId").asLong();
        assertThat(cover).isPositive();
        publish(id, created, "000005");
        JsonNode cards = success(get(API + "/articles").param("size", "100"), "000004");
        assertThat(cards.path("items").findValues("coverRevisionAssetId"))
            .anySatisfy(value -> assertThat(value.asLong()).isEqualTo(cover));

        command.put("clientRequestId", UUID.randomUUID());
        Long tagId = jdbc.queryForObject("select max(tag_id) from learning_hub.community_tags", Long.class);
        command.put("tagIds", List.of(tagId));
        JsonNode question = success(post(API + "/questions").content(json.writeValueAsString(command)), "000005");
        long questionId = question.path("contentId").asLong();
        long questionCover = question.path("draftRevision").path("assets").get(0).path("revisionAssetId").asLong();
        assertThat(questionCover).isPositive();
        publish(questionId, question, "000005");
        JsonNode questions = success(get(API + "/questions").param("size", "100"), "000004");
        assertThat(questions.path("items").findValues("coverRevisionAssetId"))
            .anySatisfy(value -> assertThat(value.asLong()).isEqualTo(questionCover));

        command.put("clientRequestId", UUID.randomUUID());
        command.put("assets", List.of(Map.of("assetId", assetId, "usage", "cover", "sortNo", 0),
            Map.of("assetId", assetId, "usage", "cover", "sortNo", 1)));
        assertThat(response(post(API + "/articles").content(json.writeValueAsString(command)), "000005")
            .path("status").asText()).isEqualTo("ERROR");

        jdbc.update("update learning_hub.community_assets set media_type='video' where asset_id=?", assetId);
        command.put("clientRequestId", UUID.randomUUID());
        command.put("assets", List.of(Map.of("assetId", assetId, "usage", "cover", "sortNo", 0)));
        assertThat(response(post(API + "/articles").content(json.writeValueAsString(command)), "000005")
            .path("status").asText()).isEqualTo("ERROR");
    }

    private JsonNode comment(long id, Long parent) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("clientRequestId", UUID.randomUUID());
        body.put("bodyMarkdown", "Synthetic comment");
        body.put("parentCommentId", parent);
        return success(post(API + "/contents/" + id + "/comments").content(json.writeValueAsString(body)), "000004");
    }

    private JsonNode create(String actor, String route, String visibility, List<Long> tags) throws Exception {
        return success(post(API + "/" + route).content(json.writeValueAsString(body(visibility, tags))), actor);
    }

    private Map<String, Object> body(String visibility, List<Long> tags) {
        Map<String, Object> body = new HashMap<>();
        body.put("clientRequestId", UUID.randomUUID());
        body.put("title", "Synthetic test article");
        body.put("bodyMarkdown", "This is synthetic content for Community API integration checks.");
        body.put("visibility", visibility);
        body.put("tagIds", tags);
        body.put("assets", List.of());
        return body;
    }

    private JsonNode publish(long id, JsonNode created, String actor) throws Exception {
        return success(post(API + "/contents/" + id + "/publish").content(json.writeValueAsString(versions(created))), actor);
    }

    private Map<String, Long> versions(JsonNode created) {
        return Map.of("expectedContentVersion", created.path("contentVersion").asLong(),
            "expectedRevisionVersion", created.path("draftRevision").path("revisionVersion").asLong());
    }

    private JsonNode success(MockHttpServletRequestBuilder request, String actor) throws Exception {
        JsonNode response = response(request, actor);
        assertThat(response.path("status").asText()).withFailMessage("API error code: %s", response.path("messageCode"))
            .isEqualTo("SUCCESS");
        return response.path("data");
    }

    private JsonNode response(MockHttpServletRequestBuilder request, String actor) throws Exception {
        return json.readTree(mvc.perform(request.with(jwt().jwt(token -> token.subject(actor).claim("av", 0)))
            .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }
}
