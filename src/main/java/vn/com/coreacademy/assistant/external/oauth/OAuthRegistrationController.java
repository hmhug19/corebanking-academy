package vn.com.coreacademy.assistant.external.oauth;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Dynamic client registration (RFC 7591) for Trợ lý ngoài clients: public clients with allowed redirect URIs. */
@RestController
@Tag(name = "Assistant external OAuth")
@SecurityRequirements
@RequiredArgsConstructor
public class OAuthRegistrationController {
    /** The registration metadata read; anything else a client sends is ignored. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Registration(@JsonProperty("client_name") String clientName,
                               @JsonProperty("redirect_uris") List<String> redirectUris,
                               @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
                               @JsonProperty("grant_types") List<String> grantTypes,
                               @JsonProperty("response_types") List<String> responseTypes) {
    }

    private final OAuthClientService clients;

    @PostMapping(value = OAuthCodes.REGISTER_PATH, consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Client OAuth tự đăng ký (RFC 7591)",
        description = """
            Công khai, giới hạn theo IP. Chỉ client public (token_endpoint_auth_method none).
            - redirect_uris: https://claude.ai/api/mcp/auth_callback, https://claude.com/api/mcp/auth_callback hoặc
              http://localhost / 127.0.0.1 / [::1] với cổng và đường dẫn bất kỳ; khác đi trả 400 invalid_redirect_uri.
            - client_name: tên do client tự khai, hiện trên trang Cho phép kết nối kèm ghi chú không xác minh.
            - Cờ tắt: 404.
            """)
    public ResponseEntity<Map<String, Object>> register(@RequestBody Registration registration,
                                                        HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(clients.register(registration, http.getRemoteAddr()));
    }
}
