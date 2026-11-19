package vn.com.coreacademy.assistant.external;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.delete;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.REPORT_VIEWER;

/** Kết nối trợ lý ngoài: create (token shown once, only its hash kept), list, revoke, and who may. */
class ExternalConnectionIT extends ExternalAssistantIT {
    static final String CONNECTIONS = "/api/v1/assistant/external-connections";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM learning_hub.external_assistant_connection");
    }

    @Test
    void createReturnsTheTokenOnceAndStoresOnlyItsHashAndPrefix() throws Exception {
        JsonNode created = api.ok(INSTRUCTOR, api.post(Map.of("name", "Claude trên máy tôi", "validDays", 30),
            CONNECTIONS));
        String token = created.path("token").asText();
        JsonNode connection = created.path("connection");

        assertThat(token).startsWith("chan_ext_").hasSizeGreaterThan(40);
        assertThat(connection.path("name").asText()).isEqualTo("Claude trên máy tôi");
        assertThat(connection.path("tokenPrefix").asText()).isEqualTo(token.substring(0, 13));
        assertThat(connection.path("kind").asText()).isEqualTo("token");
        assertThat(connection.path("lastUsedAt").isNull()).isTrue();
        OffsetDateTime expires = OffsetDateTime.parse(connection.path("expiresAt").asText());
        assertThat(expires).isBetween(OffsetDateTime.now().plusDays(29), OffsetDateTime.now().plusDays(31));

        Map<String, Object> row = jdbc.queryForMap("SELECT user_id, token_hash, token_prefix "
            + "FROM learning_hub.external_assistant_connection WHERE connection_id = ?", connection.path("id").asLong());
        assertThat(row).containsEntry("user_id", INSTRUCTOR).containsEntry("token_prefix", token.substring(0, 13))
            .containsEntry("token_hash", sha256(token));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.external_assistant_connection "
            + "WHERE token_hash = ? OR token_prefix = ?", Integer.class, token, token)).isZero();

        // The list never shows the token again.
        JsonNode listed = api.ok(INSTRUCTOR, get(CONNECTIONS));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).has("token")).isFalse();
        assertThat(listed.toString()).doesNotContain(token);
    }

    @Test
    void ninetyDaysIsAllowedAndOtherDurationsAndBlankNamesAreRefused() {
        JsonNode created = api.ok(ADMIN, api.post(Map.of("name", "Laptop", "validDays", 90), CONNECTIONS));
        assertThat(OffsetDateTime.parse(created.path("connection").path("expiresAt").asText()))
            .isAfter(OffsetDateTime.now().plusDays(89));

        JsonNode wrongDays = api.error(ADMIN, api.post(Map.of("name", "Laptop", "validDays", 365), CONNECTIONS),
            "CORE-VALIDATION_FAILED");
        assertThat(wrongDays.path("errors").get(0).path("field").asText()).isEqualTo("validDays");
        api.error(ADMIN, api.post(Map.of("name", " ", "validDays", 30), CONNECTIONS), "CORE-VALIDATION_FAILED");
        api.error(ADMIN, api.post(Map.of("name", "x".repeat(101), "validDays", 30), CONNECTIONS),
            "CORE-VALIDATION_FAILED");
    }

    @Test
    void rolesOutsideTheDraftingSetGet403AndNothingIsStored() {
        api.http(LEARNER, api.post(Map.of("name", "Laptop", "validDays", 30), CONNECTIONS), 403, "CORE-ACCESS_DENIED");
        api.http(REPORT_VIEWER, api.post(Map.of("name", "Laptop", "validDays", 30), CONNECTIONS), 403,
            "CORE-ACCESS_DENIED");
        api.http(LEARNER, get(CONNECTIONS), 403, "CORE-ACCESS_DENIED");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.external_assistant_connection",
            Integer.class)).isZero();
    }

    @Test
    void aUserListsAndRevokesOnlyTheirOwnConnections() {
        long mine = api.ok(INSTRUCTOR, api.post(Map.of("name", "Của tôi", "validDays", 30), CONNECTIONS))
            .path("connection").path("id").asLong();
        long theirs = api.ok(ADMIN, api.post(Map.of("name", "Của admin", "validDays", 30), CONNECTIONS))
            .path("connection").path("id").asLong();

        JsonNode listed = api.ok(INSTRUCTOR, get(CONNECTIONS));
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).path("id").asLong()).isEqualTo(mine);

        // Someone else's connection answers like a missing one and stays valid.
        api.error(INSTRUCTOR, delete(CONNECTIONS + "/{id}", theirs), "CORE-RESOURCE_NOT_FOUND");
        assertThat(revokedAt(theirs)).isNull();

        api.ok(INSTRUCTOR, delete(CONNECTIONS + "/{id}", mine));
        assertThat(revokedAt(mine)).isNotNull();
        assertThat(api.ok(INSTRUCTOR, get(CONNECTIONS))).isEmpty();
        // Revoking again is refused as missing: a revoked connection is gone for its user.
        api.error(INSTRUCTOR, delete(CONNECTIONS + "/{id}", mine), "CORE-RESOURCE_NOT_FOUND");
    }

    @Test
    void theStatusSaysTheFeatureIsOnAndWhetherTheCallerMayConnect() {
        JsonNode instructor = api.ok(INSTRUCTOR, get(CONNECTIONS + "/status"));
        assertThat(instructor.path("enabled").asBoolean()).isTrue();
        assertThat(instructor.path("allowed").asBoolean()).isTrue();
        assertThat(instructor.path("mcpUrl").asText()).isEqualTo("https://lms.example/api/v1/mcp");

        JsonNode learner = api.ok(LEARNER, get(CONNECTIONS + "/status"));
        assertThat(learner.path("enabled").asBoolean()).isTrue();
        assertThat(learner.path("allowed").asBoolean()).isFalse();
    }

    private OffsetDateTime revokedAt(long id) {
        return jdbc.queryForObject("SELECT revoked_at FROM learning_hub.external_assistant_connection "
            + "WHERE connection_id = ?", OffsetDateTime.class, id);
    }

    static String sha256(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(StandardCharsets.UTF_8)));
    }
}
