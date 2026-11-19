package vn.com.coreacademy.assistant.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.com.coreacademy.assessment.support.ApiClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Drives the OAuth of a Trợ lý ngoài as Claude does: register, authorize (the browser), answer the Cho phép kết nối
 * page as the signed-in user, and exchange or refresh tokens.
 */
final class OAuthFlow {
    static final String CALLBACK = "http://127.0.0.1:53682/callback";
    static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk-with-enough-entropy";

    private final MockMvc mvc;
    private final ObjectMapper json;
    private final ApiClient api;

    OAuthFlow(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
        this.api = new ApiClient(mvc, json);
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    String register(String name, String... redirectUris) {
        try {
            MockHttpServletResponse response = mvc.perform(post("/api/v1/oauth/register")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("client_name", name,
                    "redirect_uris", List.of(redirectUris))))).andReturn().getResponse();
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
            return json.readTree(response.getContentAsString()).path("client_id").asText();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The authorize answer for these parameters (null values are left out). */
    MockHttpServletResponse authorize(Map<String, String> params) {
        MockHttpServletRequestBuilder request = get("/api/v1/oauth/authorize");
        params.forEach((key, value) -> {
            if (value != null) {
                request.param(key, value);
            }
        });
        try {
            return mvc.perform(request).andReturn().getResponse();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Map<String, String> params(String clientId, String redirectUri) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("redirect_uri", redirectUri);
        params.put("code_challenge", challenge(VERIFIER));
        params.put("code_challenge_method", "S256");
        params.put("state", "state-123");
        params.put("scope", "course_drafting");
        params.put("resource", "https://lms.example/api/v1/mcp");
        return params;
    }

    /** The request id the authorize redirect sends to the Cho phép kết nối page. */
    String requestId(String clientId, String redirectUri) {
        MockHttpServletResponse response = authorize(params(clientId, redirectUri));
        assertThat(response.getStatus()).as(response.getHeader(HttpHeaders.LOCATION)).isEqualTo(302);
        String location = response.getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith("https://lms.example/tro-ly-ngoai/cho-phep?request=");
        return query(location).get("request");
    }

    JsonNode read(String user, String requestId) {
        return api.ok(user, ApiClient.get("/api/v1/assistant/oauth-requests/{id}", requestId));
    }

    String approve(String user, String requestId) {
        return api.ok(user, api.post(Map.of(), "/api/v1/assistant/oauth-requests/{id}/approve", requestId))
            .path("redirectUrl").asText();
    }

    String deny(String user, String requestId) {
        return api.ok(user, api.post(Map.of(), "/api/v1/assistant/oauth-requests/{id}/deny", requestId))
            .path("redirectUrl").asText();
    }

    ApiClient api() {
        return api;
    }

    /** The code a full authorize and Cho phép give, for this user and client. */
    String code(String user, String clientId) {
        return query(approve(user, requestId(clientId, CALLBACK))).get("code");
    }

    /** POSTs a form to the token endpoint. */
    MockHttpServletResponse token(Map<String, String> form) {
        return form("/api/v1/oauth/token", form);
    }

    MockHttpServletResponse form(String path, Map<String, String> form) {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        form.forEach((key, value) -> {
            if (value != null) {
                request.param(key, value);
            }
        });
        try {
            return mvc.perform(request).andReturn().getResponse();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    JsonNode body(MockHttpServletResponse response) {
        try {
            return json.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Map<String, String> query(String url) {
        Map<String, String> values = new LinkedHashMap<>();
        String query = URI.create(url).getRawQuery();
        if (query == null) {
            return values;
        }
        for (String pair : query.split("&")) {
            int at = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
                URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
}
