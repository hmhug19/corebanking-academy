package vn.com.coreacademy.assistant.external.oauth;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import vn.com.coreacademy.assistant.external.oauth.OAuthAuthorizations.Authorize;
import vn.com.coreacademy.assistant.external.oauth.OAuthAuthorizations.Started;

/**
 * The authorization endpoint: checks a client's request and sends the browser to the Cho phép kết nối page of the web
 * app, which signs the user in as usual. A request whose client or redirect URI is wrong gets a page saying so and is
 * never redirected; any other error goes back to the client's redirect URI (RFC 6749 §4.1.2.1).
 */
@RestController
@Tag(name = "Assistant external OAuth")
@SecurityRequirements
@RequiredArgsConstructor
public class OAuthAuthorizeController {
    private final OAuthAuthorizations authorizations;
    private final OAuthClientAddress address;
    private final OAuthServer server;

    @GetMapping(OAuthCodes.AUTHORIZE_PATH)
    @Operation(summary = "Bắt đầu cấp quyền (authorization code + PKCE S256)",
        description = """
            Công khai, giới hạn yêu cầu mới theo IP người gọi (quá: 429 trang lỗi).
            - Đúng client và redirect_uri: lưu yêu cầu (10 phút), đặt cookie chan_oauth_request (HttpOnly, SameSite=Lax,
              path /api/v1/assistant/oauth-requests) rồi 302 tới {app-base-url}/tro-ly-ngoai/cho-phep?request={id}.
              Chỉ trình duyệt có cookie đó trả lời được yêu cầu.
            - Sai client/redirect_uri: 400 trang lỗi, không chuyển hướng.
            - Lỗi khác (response_type, code_challenge, scope, resource): 302 về redirect_uri với error và state.
            - Cờ tắt: 404.
            """)
    public ResponseEntity<String> authorize(@RequestParam(name = "response_type", required = false) String responseType,
                                            @RequestParam(name = "client_id", required = false) String clientId,
                                            @RequestParam(name = "redirect_uri", required = false) String redirectUri,
                                            @RequestParam(name = "code_challenge", required = false)
                                            String codeChallenge,
                                            @RequestParam(name = "code_challenge_method", required = false)
                                            String codeChallengeMethod,
                                            @RequestParam(name = "state", required = false) String state,
                                            @RequestParam(name = "scope", required = false) String scope,
                                            @RequestParam(name = "resource", required = false) String resource,
                                            HttpServletRequest http) {
        try {
            Started started = authorizations.authorize(new Authorize(responseType, clientId, redirectUri,
                codeChallenge, codeChallengeMethod, state, scope, resource), address.of(http));
            ResponseEntity.BodyBuilder redirect = ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, started.location()).cacheControl(CacheControl.noStore());
            if (started.browserToken() != null) {
                redirect.header(HttpHeaders.SET_COOKIE, browserCookie(started.browserToken()).toString());
            }
            return redirect.build();
        } catch (OAuthAuthorizations.NoRedirect refusal) {
            String page = "<!doctype html><html lang=\"vi\"><meta charset=\"utf-8\"><title>CHAN Academy</title>"
                + "<p>Không thể kết nối trợ lý ngoài: " + HtmlUtils.htmlEscape(refusal.getMessage())
                + "</p><p>Hãy thử kết nối lại từ ứng dụng trợ lý.</p></html>";
            return ResponseEntity.status(refusal.status()).contentType(new MediaType(MediaType.TEXT_HTML,
                    StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore()).body(page);
        }
    }

    /**
     * Sent only to the Cho phép kết nối API on this origin. Lax, because the browser arrives here by a top-level
     * redirect from the client's site; Secure whenever the web app is served over https.
     */
    private ResponseCookie browserCookie(String token) {
        return ResponseCookie.from(OAuthCodes.BROWSER_COOKIE, token).httpOnly(true)
            .secure(server.issuer().startsWith("https://")).sameSite("Lax").path(OAuthCodes.CONSENT_API)
            .maxAge(OAuthCodes.REQUEST_TTL).build();
    }
}
