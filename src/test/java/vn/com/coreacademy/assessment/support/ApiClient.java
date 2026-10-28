package vn.com.coreacademy.assessment.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * Gọi API như FE: JWT theo mã cán bộ, đọc envelope BaseResponse và kiểm tra hợp đồng chung (status IN HOA,
 * messageCode, message/messageTitle tiếng Việt, data luôn có khóa).
 */
public final class ApiClient {
    private final MockMvc mvc;
    private final ObjectMapper json;

    public ApiClient(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    public static MockHttpServletRequestBuilder get(String url, Object... args) {
        return MockMvcRequestBuilders.get(url, args);
    }

    public MockHttpServletRequestBuilder post(Object body, String url, Object... args) {
        return withBody(MockMvcRequestBuilders.post(url, args), body);
    }

    public MockHttpServletRequestBuilder put(Object body, String url, Object... args) {
        return withBody(MockMvcRequestBuilders.put(url, args), body);
    }

    public MockHttpServletRequestBuilder patch(Object body, String url, Object... args) {
        return withBody(MockMvcRequestBuilders.patch(url, args), body);
    }

    public static MockHttpServletRequestBuilder delete(String url, Object... args) {
        return MockMvcRequestBuilders.delete(url, args);
    }

    /** Thành công: HTTP 200, status SUCCESS, CORE-SUCCESS; trả node data. */
    public JsonNode ok(String user, MockHttpServletRequestBuilder request) {
        JsonNode body = call(user, request, 200);
        assertThat(body.path("status").asText()).as(body.toPrettyString()).isEqualTo("SUCCESS");
        assertThat(body.path("messageCode").asText()).isEqualTo("CORE-SUCCESS");
        assertThat(body.has("data")).isTrue();
        assertThat(body.has("errors")).as("errors chỉ xuất hiện khi lỗi").isFalse();
        return body.path("data");
    }

    /** Lỗi nghiệp vụ/validation: HTTP 200, status ERROR, đúng messageCode; trả cả body để kiểm errors. */
    public JsonNode error(String user, MockHttpServletRequestBuilder request, String messageCode) {
        JsonNode body = call(user, request, 200);
        assertThat(body.path("status").asText()).as(body.toPrettyString()).isEqualTo("ERROR");
        assertThat(body.path("messageCode").asText()).as(body.toPrettyString()).isEqualTo(messageCode);
        assertThat(body.path("data").isNull()).isTrue();
        return body;
    }

    /** Lỗi giao thức/phân quyền giữ HTTP status tương ứng (401/403/400...). */
    public JsonNode http(String user, MockHttpServletRequestBuilder request, int status, String messageCode) {
        JsonNode body = call(user, request, status);
        assertThat(body.path("status").asText()).isEqualTo("ERROR");
        assertThat(body.path("messageCode").asText()).as(body.toPrettyString()).isEqualTo(messageCode);
        return body;
    }

    public MockHttpServletResponse raw(String user, MockHttpServletRequestBuilder request) {
        try {
            if (user != null) {
                request.with(jwt().jwt(token -> token.subject(user)));
            }
            return mvc.perform(request).andReturn().getResponse();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode call(String user, MockHttpServletRequestBuilder request, int expectedStatus) {
        MockHttpServletResponse response = raw(user, request);
        try {
            String content = response.getContentAsString(StandardCharsets.UTF_8);
            assertThat(response.getStatus()).as(content).isEqualTo(expectedStatus);
            JsonNode body = json.readTree(content);
            assertEnvelope(body);
            return body;
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertEnvelope(JsonNode body) {
        for (String field : new String[] {"responseId", "timestamp", "status", "messageCode", "message",
            "messageTitle"}) {
            assertThat(body.hasNonNull(field)).as("envelope thiếu " + field + ": " + body).isTrue();
        }
        assertThat(body.path("status").asText()).isIn("SUCCESS", "ERROR");
        assertThat(body.path("messageCode").asText()).matches("[A-Z]+-[A-Z_]+");
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, Object body) {
        try {
            String content = body instanceof String text ? text : json.writeValueAsString(body);
            return builder.contentType(MediaType.APPLICATION_JSON).content(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
