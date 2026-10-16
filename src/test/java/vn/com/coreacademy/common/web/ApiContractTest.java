package vn.com.coreacademy.common.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.common.dto.*;
import vn.com.coreacademy.common.exception.ApiExceptionHandler;
import vn.com.coreacademy.config.CorsConfig;
import vn.com.coreacademy.identity.config.IdentitySecurityConfig;
import vn.com.coreacademy.identity.controller.AuthController;
import vn.com.coreacademy.identity.dto.TokenResponse;
import vn.com.coreacademy.identity.security.CurrentUser;
import vn.com.coreacademy.identity.security.UserJwtAuthenticationConverter;
import vn.com.coreacademy.identity.service.AuthService;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class, properties = {
        "app.jwt.secret=test-only-secret-with-at-least-32-bytes",
        "app.jwt.expiry-minutes=5", "app.cors.origins=http://localhost:4200"})
@Import({IdentitySecurityConfig.class, CorsConfig.class, CurrentUser.class,
        ApiExceptionHandler.class, RequestIdFilter.class, ApiSecurityErrorHandler.class})
class ApiContractTest {
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;
    @MockitoBean AuthService service;
    @MockitoBean org.springframework.security.core.userdetails.UserDetailsService userDetails;
    @MockitoBean UserJwtAuthenticationConverter converter;
    private static final String LOGIN = "/api/v1/auth/login";

    @Test void wrapsSuccessAndPreservesTypedDataAndRequestCorrelation() throws Exception {
        when(service.login(any())).thenReturn(new TokenResponse("test-token", "Bearer", 300));
        mvc.perform(post(LOGIN).header("X-Request-Id", "fe-request-001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andExpect(header().string("X-Request-Id", "fe-request-001"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.responseId").value("fe-request-001"))
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.messageCode").value("CORE-0000"))
                .andExpect(jsonPath("$.data.accessToken").value("test-token"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(300))
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertNull(MDC.get("requestId"));
        verify(service).login(new vn.com.coreacademy.identity.dto.LoginRequest("demo", "test-password"));
    }
    @Test void validatesDirectRequestFieldsWithoutEchoingSecrets() throws Exception {
        for (String body : new String[]{"{}", "{\"data\":null}", "{\"data\":{\"username\":\"demo\",\"password\":\"top-secret\"}}"}) {
            String result = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("ERROR"))
                    .andExpect(jsonPath("$.messageCode").value("CORE-0400-VALIDATION"))
                    .andExpect(jsonPath("$.errors.length()").value(2)).andReturn().getResponse().getContentAsString();
            assertFalse(result.contains("top-secret"));
        }
        var response = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"top-secret\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("username"))
                .andReturn().getResponse();
        assertFalse(response.getContentAsString().contains("top-secret"));
        verifyNoInteractions(service);
    }
    @Test void malformedJsonIsSafeAndCorrelated() throws Exception {
        var response = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("{\"data\":bad-password-secret"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.messageCode").value("CORE-0400-JSON"))
                .andReturn().getResponse();
        assertFalse(response.getContentAsString().contains("bad-password-secret"));
        assertEquals(response.getHeader("X-Request-Id"), mapper.readTree(response.getContentAsString()).get("responseId").asText());
    }
    @Test void replacesUnsafeRequestIdAndRestoresExistingMdc() throws Exception {
        MDC.put("requestId", "outer-context");
        try {
            var response = mvc.perform(post(LOGIN).header("X-Request-Id", "invalid id with spaces")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest()).andReturn().getResponse();
            assertNotEquals("invalid id with spaces", response.getHeader("X-Request-Id"));
            assertEquals(response.getHeader("X-Request-Id"), mapper.readTree(response.getContentAsString()).get("responseId").asText());
            assertEquals("outer-context", MDC.get("requestId"));
        } finally { MDC.remove("requestId"); }
    }
    @Test void authenticationErrorsFromMvcAndSecurityUseSameContract() throws Exception {
        when(service.login(any())).thenThrow(new BadCredentialsException("do-not-expose"));
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.messageCode").value("CORE-0401"));
        for (String token : new String[]{"", "Bearer broken-token"}) {
            var request = get("/api/v1/auth/me").header("X-Request-Id", "security-case");
            if (!token.isEmpty()) request.header("Authorization", token);
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(jsonPath("$.responseId").value("security-case"))
                    .andExpect(jsonPath("$.messageCode").value("CORE-0401"));
        }
    }
    @Test void deniedActionHas403Envelope() throws Exception {
        doThrow(new AccessDeniedException("internal-detail")).when(service).logout(anyString(), anyLong());
        mvc.perform(post("/api/v1/auth/logout").with(jwt().jwt(j -> j.subject("u").claim("av", 0L))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.messageCode").value("CORE-0403"));
    }
    @Test void logoutReturns200WithExplicitNullData() throws Exception {
        var response = mvc.perform(post("/api/v1/auth/logout").with(jwt().jwt(j -> j.subject("u").claim("av", 0L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn().getResponse();
        assertTrue(mapper.readTree(response.getContentAsString()).has("data"));
        assertTrue(mapper.readTree(response.getContentAsString()).get("data").isNull());
        verify(service).logout("u", 0L);
    }
    @Test void unexpectedFailuresHideInternalDetails() throws Exception {
        when(service.login(any())).thenThrow(new IllegalStateException("private-internal-detail"));
        var response = mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"wrong\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.messageCode").value("CORE-0500"))
                .andReturn().getResponse();
        assertFalse(response.getContentAsString().contains("private-internal-detail"));
    }
    @Test void controlledBusinessErrorKeepsItsCodeAndHttpStatus() throws Exception {
        when(service.login(any())).thenThrow(new vn.com.coreacademy.common.exception.BusinessException(
                vn.com.coreacademy.common.exception.ResponseCode.CONFLICT));
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demo\",\"password\":\"wrong\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.messageCode").value("CORE-0409"));
    }
    @Test void wrongMethodAndContentTypeKeepHttpSemantics() throws Exception {
        mvc.perform(get(LOGIN).with(jwt())).andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow")).andExpect(jsonPath("$.messageCode").value("CORE-0405"));
        mvc.perform(post(LOGIN).contentType(MediaType.TEXT_PLAIN).content("body"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.messageCode").value("CORE-0415"));
    }
    @Test void unsupportedAcceptAndUnknownApiPathStillReturnJsonErrors() throws Exception {
        when(service.login(any())).thenReturn(new TokenResponse("test-token", "Bearer", 300));
        mvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_PLAIN)
                        .content("{\"username\":\"demo\",\"password\":\"test\"}"))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.messageCode").value("CORE-0406"));
        mvc.perform(get("/api/v1/nonexistent").with(jwt()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.messageCode").value("CORE-0404"));
    }
    @Test void corsAllowsCorrelationHeaderAndExposesItToFrontend() throws Exception {
        mvc.perform(options(LOGIN).header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type,X-Request-Id"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Expose-Headers", "X-Request-Id"));
    }
}
