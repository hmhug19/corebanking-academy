package vn.com.coreacademy.assistant.external.oauth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assistant.external.ExternalMcpSecurityConfig;

/**
 * OAuth discovery for the MCP endpoint: the protected resource metadata (RFC 9728) and the authorization server
 * metadata (RFC 8414), so a client needs only the MCP URL. Public; 404 while the Trợ lý ngoài is off.
 */
@RestController
@Tag(name = "Assistant external OAuth")
@SecurityRequirements
@RequiredArgsConstructor
public class OAuthMetadataController {
    private final OAuthServer server;

    @GetMapping(value = {OAuthCodes.PROTECTED_RESOURCE_PATH, OAuthCodes.PROTECTED_RESOURCE_PATH + ExternalMcpSecurityConfig.MCP_PATH},
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Protected resource metadata của endpoint MCP (RFC 9728)",
        description = "Công khai. Client MCP đọc để biết authorization server. Cờ tắt: 404.")
    public Map<String, Object> protectedResource() {
        server.requireEnabled();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", server.resource());
        body.put("authorization_servers", List.of(server.issuer()));
        body.put("scopes_supported", List.of(OAuthCodes.SCOPE));
        body.put("bearer_methods_supported", List.of("header"));
        body.put("resource_name", "CHAN Academy");
        return body;
    }

    @GetMapping(value = OAuthCodes.AUTHORIZATION_SERVER_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Authorization server metadata (RFC 8414)",
        description = "Công khai. Code + PKCE S256, refresh token, client tự đăng ký, client public. Cờ tắt: 404.")
    public Map<String, Object> authorizationServer() {
        server.requireEnabled();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", server.issuer());
        body.put("authorization_endpoint", server.url(OAuthCodes.AUTHORIZE_PATH));
        body.put("token_endpoint", server.url(OAuthCodes.TOKEN_PATH));
        body.put("registration_endpoint", server.url(OAuthCodes.REGISTER_PATH));
        body.put("revocation_endpoint", server.url(OAuthCodes.REVOKE_PATH));
        body.put("scopes_supported", List.of(OAuthCodes.SCOPE));
        body.put("response_types_supported", List.of("code"));
        body.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        body.put("code_challenge_methods_supported", List.of("S256"));
        body.put("token_endpoint_auth_methods_supported", List.of("none"));
        body.put("revocation_endpoint_auth_methods_supported", List.of("none"));
        return body;
    }
}
