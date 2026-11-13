package vn.com.coreacademy.identity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.service.TokenService;
import vn.com.coreacademy.security.SecurityConstants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ROOT;

/** Logout ends the session that asked for it; account-wide changes still end every session. */
class SessionRevocationIT extends AssessmentIntegrationTest {
    // Users this class changes: the learner of the second seed account and the report viewer, not the shared learner.
    private static final String TWO_BROWSERS = "000007";
    private static final String LEGACY = "000006";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TokenService tokens;
    @Autowired
    private UserRepository users;
    @Autowired
    private JwtEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;

    private String signIn(String userId) {
        return tokens.issue(users.findById(userId).orElseThrow()).accessToken();
    }

    private int me(String token) throws Exception {
        return mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token)).andReturn()
            .getResponse().getStatus();
    }

    private int logout(String token) throws Exception {
        return mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token)).andReturn()
            .getResponse().getStatus();
    }

    @Test
    void loggingOutInOneBrowserKeepsTheOtherSignedIn() throws Exception {
        String browserA = signIn(TWO_BROWSERS);
        String browserB = signIn(TWO_BROWSERS);
        assertThat(me(browserA)).isEqualTo(200);
        assertThat(me(browserB)).isEqualTo(200);

        assertThat(logout(browserA)).isEqualTo(200);

        assertThat(me(browserA)).isEqualTo(401);
        assertThat(me(browserB)).isEqualTo(200);
        assertThat(logout(browserA)).isEqualTo(401);
        Map<String, Object> audit = jdbc.queryForMap("""
            select details::text as detail from learning_hub.audit_log
            where action = 'USER_LOGOUT' and actor_id = ? order by audit_id desc limit 1
            """, TWO_BROWSERS);
        assertThat(String.valueOf(audit.get("detail"))).contains("\"allSessions\": false");
    }

    @Test
    void aRoleChangeStillEndsEverySession() throws Exception {
        String browserA = signIn(TWO_BROWSERS);
        String browserB = signIn(TWO_BROWSERS);
        Long roleId = jdbc.queryForObject("select role_id from learning_hub.roles where code = 'REPORT_VIEWER'",
            Long.class);
        ApiClient api = new ApiClient(mvc, json);

        var response = api.raw(ROOT, api.post(Map.of("roleId", roleId, "scope", "GLOBAL"),
            "/api/v1/users/{userId}/role-grants", TWO_BROWSERS));
        assertThat(response.getStatus()).isEqualTo(201);
        JsonNode granted = json.readTree(response.getContentAsString()).path("data");

        assertThat(me(browserA)).isEqualTo(401);
        assertThat(me(browserB)).isEqualTo(401);
        api.ok(ROOT, ApiClient.delete("/api/v1/users/{userId}/role-grants/{grantId}", TWO_BROWSERS,
            granted.path("grantId").asLong()));
    }

    @Test
    void aTokenFromBeforeSessionsIsStillAcceptedAndItsLogoutEndsEverySession() throws Exception {
        var user = users.findById(LEGACY).orElseThrow();
        Instant now = Instant.now();
        String legacy = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().issuer(SecurityConstants.JWT_ISSUER).subject(LEGACY).issuedAt(now)
                .expiresAt(now.plus(10, ChronoUnit.MINUTES))
                .claim(SecurityConstants.AUTH_VERSION_CLAIM, user.getAuthorizationVersion()).build()))
            .getTokenValue();
        String current = signIn(LEGACY);
        assertThat(me(legacy)).isEqualTo(200);

        assertThat(logout(legacy)).isEqualTo(200);

        assertThat(me(legacy)).isEqualTo(401);
        assertThat(me(current)).isEqualTo(401);
    }
}
