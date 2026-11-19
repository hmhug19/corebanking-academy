package vn.com.coreacademy.assistant.external.oauth;

import java.nio.charset.StandardCharsets;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import vn.com.coreacademy.assistant.external.oauth.OAuthAuthorizations.Authorize;

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

    @GetMapping(OAuthCodes.AUTHORIZE_PATH)
    @Operation(summary = "Bắt đầu cấp quyền (authorization code + PKCE S256)",
        description = """
            Công khai. Đúng client và redirect_uri: lưu yêu cầu (10 phút) rồi 302 tới
            {app-base-url}/tro-ly-ngoai/cho-phep?request={id}. Sai client/redirect_uri: 400 trang lỗi, không chuyển
            hướng. Lỗi khác (response_type, code_challenge, scope, resource): 302 về redirect_uri với error và state.
            Cờ tắt: 404.
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
                                            @RequestParam(name = "resource", required = false) String resource) {
        try {
            String location = authorizations.authorize(new Authorize(responseType, clientId, redirectUri,
                codeChallenge, codeChallengeMethod, state, scope, resource));
            return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, location)
                .cacheControl(CacheControl.noStore()).build();
        } catch (OAuthAuthorizations.NoRedirect refusal) {
            String page = "<!doctype html><html lang=\"vi\"><meta charset=\"utf-8\"><title>CHAN Academy</title>"
                + "<p>Không thể kết nối trợ lý ngoài: " + HtmlUtils.htmlEscape(refusal.getMessage())
                + "</p><p>Hãy thử kết nối lại từ ứng dụng trợ lý.</p></html>";
            return ResponseEntity.badRequest().contentType(new MediaType(MediaType.TEXT_HTML,
                    StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore()).body(page);
        }
    }
}
