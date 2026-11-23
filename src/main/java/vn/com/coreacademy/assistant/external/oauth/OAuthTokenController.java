package vn.com.coreacademy.assistant.external.oauth;

import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The token and revocation endpoints a Trợ lý ngoài client calls, form-encoded, as a public client. */
@RestController
@Tag(name = "Assistant external OAuth")
@SecurityRequirements
@RequiredArgsConstructor
public class OAuthTokenController {
    private final OAuthTokens tokens;

    @PostMapping(value = OAuthCodes.TOKEN_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Đổi code lấy token, hoặc refresh (RFC 6749, PKCE RFC 7636)",
        description = """
            Công khai, client public (client_id, không secret).
            - authorization_code: code (một lần, 60 giây), redirect_uri, client_id, code_verifier (S256). Code dùng lại
              thì kết nối của nó bị thu hồi.
            - refresh_token: đổi cặp token mới; refresh token cũ hết hiệu lực, dùng lại nó thì kết nối bị thu hồi.
            - Trả access_token (chan_oat_, 1 giờ), refresh_token (chan_ort_), expires_in, scope course_drafting. Kết nối
              hết hạn 30 ngày sau lần refresh gần nhất. Lỗi: 400 invalid_request/invalid_grant/unsupported_grant_type,
              401 invalid_client. Cờ tắt: 404.
            """)
    public ResponseEntity<Map<String, Object>> token(@RequestParam MultiValueMap<String, String> form) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
            .body(tokens.token(form.toSingleValueMap()));
    }

    @PostMapping(value = OAuthCodes.REVOKE_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    @Operation(summary = "Thu hồi token (RFC 7009)",
        description = "Công khai. token: access hoặc refresh token; thu hồi cả kết nối. Luôn 200. Cờ tắt: 404.")
    public ResponseEntity<Void> revoke(@RequestParam MultiValueMap<String, String> form) {
        tokens.revoke(form.toSingleValueMap());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
}
