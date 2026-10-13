package vn.com.coreacademy.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.common.exception.ApiExceptionHandler;
import vn.com.coreacademy.config.CorsConfig;
import vn.com.coreacademy.identity.config.IdentitySecurityConfig;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.entity.UserCredential;
import vn.com.coreacademy.identity.enums.UserStatus;
import vn.com.coreacademy.identity.mapper.UserMapperImpl;
import vn.com.coreacademy.identity.repository.PermissionRepository;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.identity.repository.UserCredentialRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.security.CurrentUser;
import vn.com.coreacademy.identity.security.LocalUserDetailsService;
import vn.com.coreacademy.identity.security.UserJwtAuthenticationConverter;
import vn.com.coreacademy.identity.service.AuthService;
import vn.com.coreacademy.identity.service.AuthorizationService;
import vn.com.coreacademy.identity.service.LoginUserResolver;
import vn.com.coreacademy.identity.service.TokenService;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class, properties = {
        "app.jwt.secret=only-for-tests-32-bytes-secret-key-123456",
        "app.jwt.expiry-minutes=5",
        "app.cors.origins=http://localhost:4200"
})
@Import({IdentitySecurityConfig.class, CorsConfig.class, CurrentUser.class,
        LocalUserDetailsService.class, UserJwtAuthenticationConverter.class, LoginUserResolver.class,
        AuthService.class, TokenService.class, AuthorizationService.class, ApiExceptionHandler.class, UserMapperImpl.class})
class AuthenticationApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PasswordEncoder encoder;
    @Autowired private JwtDecoder decoder;
    @MockitoBean private UserRepository users;
    @MockitoBean private UserCredentialRepository credentials;
    @MockitoBean private RoleAssignmentRepository assignments;
    @MockitoBean private PermissionRepository permissions;
    private UserAccount user;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        user.setId("00183617");
        user.setUsername("admin");
        user.setFullName("Demo Admin");
        UserCredential credential = new UserCredential();
        credential.setUserId(user.getId());
        credential.setUsername(user.getUsername());
        credential.setPasswordHash(encoder.encode("correct-password"));
        when(credentials.findById(user.getId())).thenReturn(Optional.of(credential));
        user.setAuthorizationVersion(2L);
        when(users.findLoginCandidates("admin", "admin")).thenReturn(List.of(user));
        when(users.findLoginCandidates(user.getId(), user.getId())).thenReturn(List.of(user));
        when(users.findLoginCandidates("ADMIN", "admin")).thenReturn(List.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(assignments.findActiveForUser(user.getId())).thenReturn(List.of());
    }

    @Test
    void loginProducesHs256TokenAndMePreservesResponseContract() throws Exception {
        String token = login();
        Jwt jwt = decoder.decode(token);
        assertEquals("HS256", jwt.getHeaders().get("alg"));
        assertEquals("00183617", jwt.getSubject());
        assertEquals(300L, jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value("00183617"))
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.status").value("active"))
                .andExpect(jsonPath("$.authorization.roles").isArray())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist());
    }

    @Test
    void wrongPasswordReturnsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void employeeIdAndUppercaseUsernameBothAuthenticateSameUser() throws Exception {
        for (String identifier : List.of("00183617", "ADMIN")) {
            String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(java.util.Map.of(
                                    "username", identifier, "password", "correct-password"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals("00183617", decoder.decode(mapper.readTree(response).get("accessToken").asText()).getSubject());
        }
    }

    @Test
    void userWithoutLocalCredentialsCannotLogin() throws Exception {
        when(credentials.findById(user.getId())).thenReturn(Optional.empty());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"correct-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ambiguousIdentifierCannotLogin() throws Exception {
        UserAccount other = new UserAccount();
        other.setId("another-id");
        other.setUsername(user.getId());
        when(users.findLoginCandidates(user.getId(), user.getId())).thenReturn(List.of(user, other));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"00183617\",\"password\":\"correct-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lockedAccountCannotLoginOrReuseIssuedToken() throws Exception {
        String token = login();
        user.setStatus(UserStatus.LOCKED);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"correct-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingAuthorizationVersionRevokesToken() throws Exception {
        String token = login();
        user.setAuthorizationVersion(3L);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meRequiresAuthenticationAndLoginValidatesInput() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresInSeconds").value(300))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("accessToken").asText();
    }
}
