package vn.com.coreacademy.assistant.external;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Drives {@code /api/v1/mcp} as an MCP client would over Streamable HTTP: JSON-RPC requests posted with a bearer
 * token, through the whole servlet filter chain (security included) and the MCP server's router.
 */
final class McpClient {
    static final String MCP = "/api/v1/mcp";
    private static final AtomicInteger IDS = new AtomicInteger();

    private final MockMvc mvc;
    private final ObjectMapper json;

    McpClient(MockMvc mvc, ObjectMapper json) {
        this.mvc = mvc;
        this.json = json;
    }

    /** The raw HTTP answer to one JSON-RPC request. */
    MockHttpServletResponse raw(String authorization, String method, Object params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", IDS.incrementAndGet());
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }
        try {
            return raw(authorization, json.writeValueAsString(request));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    MockHttpServletResponse raw(String authorization, String body) {
        try {
            var builder = post(MCP).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream").content(body);
            if (authorization != null) {
                builder.header(HttpHeaders.AUTHORIZATION, authorization);
            }
            return mvc.perform(builder).andReturn().getResponse();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** The {@code result} of a JSON-RPC request answered with HTTP 200 and no JSON-RPC error. */
    JsonNode result(String token, String method, Object params) {
        JsonNode body = body(raw("Bearer " + token, method, params), 200);
        assertThat(body.has("error")).as(body.toString()).isFalse();
        return body.path("result");
    }

    JsonNode initialize(String token) {
        return result(token, "initialize", Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of(),
            "clientInfo", Map.of("name", "it", "version", "1")));
    }

    /** A tool call's result: {@code isError} and the parsed JSON of its text content. */
    record ToolResult(boolean error, String text, JsonNode data) {
    }

    ToolResult call(String token, String tool, Map<String, Object> arguments) {
        JsonNode result = result(token, "tools/call", Map.of("name", tool, "arguments", arguments));
        String text = result.path("content").get(0).path("text").asText();
        JsonNode data = null;
        if (!result.path("isError").asBoolean()) {
            try {
                data = json.readTree(text);
            } catch (Exception exception) {
                throw new IllegalStateException(text, exception);
            }
        }
        return new ToolResult(result.path("isError").asBoolean(), text, data);
    }

    ToolResult ok(String token, String tool, Map<String, Object> arguments) {
        ToolResult result = call(token, tool, arguments);
        assertThat(result.error()).as(result.text()).isFalse();
        return result;
    }

    ToolResult refused(String token, String tool, Map<String, Object> arguments) {
        ToolResult result = call(token, tool, arguments);
        assertThat(result.error()).as(result.text()).isTrue();
        return result;
    }

    JsonNode body(MockHttpServletResponse response, int status) {
        try {
            String content = response.getContentAsString(StandardCharsets.UTF_8);
            assertThat(response.getStatus()).as(content).isEqualTo(status);
            return json.readTree(content);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
