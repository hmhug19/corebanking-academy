package vn.com.coreacademy.assistant.external.oauth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assistant.external.oauth.OAuthAuthorizations.ConsentRequest;
import vn.com.coreacademy.assistant.external.oauth.OAuthAuthorizations.ConsentResult;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

/**
 * The Cho phép kết nối page's API, with the user's sign-in JWT: what a pending request asks, and the user's answer.
 * The page then sends the browser to the returned URL.
 */
@RestController
@RequestMapping(value = OAuthCodes.CONSENT_API + "/{requestId}", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assistant external OAuth")
@StandardApiErrors
@RequiredArgsConstructor
public class OAuthConsentController {
    private static final String WHO = """
        - Chỉ vai trò quản lý khóa học (INSTRUCTOR, ADMIN, SUPER_ADMIN) có chức năng AI_ASSISTANT; người khác nhận 403.
        - Yêu cầu đã dùng, hết hạn (10 phút), không có, hoặc mở từ trình duyệt khác trình duyệt đã bắt đầu (không có
          cookie chan_oauth_request khớp): CORE-RESOURCE_NOT_FOUND. Cờ tắt: 404.
        """;

    private final OAuthAuthorizations authorizations;

    @GetMapping
    @Operation(summary = "Yêu cầu cấp quyền đang chờ Cho phép kết nối", description = WHO)
    public BaseResponse<ConsentRequest> read(@PathVariable String requestId,
                                             @CookieValue(name = OAuthCodes.BROWSER_COOKIE, required = false)
                                             String browser) {
        return BaseResponse.ok(authorizations.read(requestId, browser));
    }

    @PostMapping("/approve")
    @Operation(summary = "Cho phép: tạo Kết nối trợ lý ngoài, trả URL quay về client kèm code một lần",
        description = """
            Mỗi kết nối mới cần một lần Cho phép. Đã có 10 kết nối còn hiệu lực: CORE-STATE_CONFLICT trường
            connections (yêu cầu vẫn mở để thử lại sau khi thu hồi bớt).
            """ + WHO)
    public BaseResponse<ConsentResult> approve(@PathVariable String requestId,
                                               @CookieValue(name = OAuthCodes.BROWSER_COOKIE, required = false)
                                               String browser) {
        return BaseResponse.ok(authorizations.approve(requestId, browser));
    }

    @PostMapping("/deny")
    @Operation(summary = "Từ chối: trả URL quay về client kèm error=access_denied", description = WHO)
    public BaseResponse<ConsentResult> deny(@PathVariable String requestId,
                                            @CookieValue(name = OAuthCodes.BROWSER_COOKIE, required = false)
                                            String browser) {
        return BaseResponse.ok(authorizations.deny(requestId, browser));
    }
}
