package vn.com.coreacademy.assistant.external.oauth;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assistant.external.ExternalConnectionRepository;
import vn.com.coreacademy.assistant.external.ExternalConnectionService;
import vn.com.coreacademy.assistant.external.oauth.OAuthClientRepository.ClientRow;
import vn.com.coreacademy.assistant.external.oauth.OAuthGrantRepository.RequestRow;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.security.SecurityContextUtils;

/**
 * From a client's authorize to a one-time code: checks the request, keeps it while the user reads the Cho phép kết
 * nối page, and on Cho phép makes a Kết nối trợ lý ngoài and its code. Every new connection needs its own Cho phép;
 * a client is never approved on its own because it was approved before.
 */
@Service
@RequiredArgsConstructor
public class OAuthAuthorizations {
    /** What the authorization endpoint receives. */
    public record Authorize(String responseType, String clientId, String redirectUri, String codeChallenge,
                            String codeChallengeMethod, String state, String scope, String resource) {
    }

    @Schema(description = "Yêu cầu cấp quyền đang chờ trên trang Cho phép kết nối.")
    public record ConsentRequest(
        @Schema(description = "Tên client tự khai; CHAN Academy không xác minh") String clientName,
        @Schema(description = "Nơi trình duyệt quay về: claude.ai, claude.com hoặc máy của người dùng")
        String redirectHost,
        @Schema(description = "Số ngày kết nối còn hiệu lực khi không dùng") int idleDays,
        @Schema(description = "Người dùng đã có đủ số kết nối còn hiệu lực tối đa") boolean atLimit,
        int maxConnections) {
    }

    @Schema(description = "URL trình duyệt chuyển tới sau khi Cho phép hoặc Từ chối.")
    public record ConsentResult(String redirectUrl) {
    }

    /** A request that cannot be sent back to the client: its client or redirect URI is not one to trust. */
    static final class NoRedirect extends RuntimeException {
        NoRedirect(String message) {
            super(message);
        }
    }

    private final OAuthServer server;
    private final OAuthClientRepository clients;
    private final OAuthGrantRepository grants;
    private final ExternalConnectionRepository connections;
    private final ExternalConnectionService connectionService;
    private final OAuthAudit audit;

    /** Where to send the browser: the Cho phép kết nối page, or back to the client with an error. */
    public String authorize(Authorize request) {
        server.requireEnabled();
        if (request.clientId() == null || request.clientId().isBlank()) {
            throw new NoRedirect("thiếu client_id.");
        }
        ClientRow client = clients.find(request.clientId())
            .orElseThrow(() -> new NoRedirect("ứng dụng chưa đăng ký với CHAN Academy."));
        String redirect = request.redirectUri();
        if (redirect == null || client.redirectUris().stream().noneMatch(uri -> OAuthRedirects.matches(uri, redirect))
            || !OAuthRedirects.allowed(redirect)) {
            audit.record(OAuthAudit.Event.AUTHORIZE_REFUSED, null, null, client.clientId(), "redirect_uri");
            throw new NoRedirect("địa chỉ quay về không khớp với địa chỉ ứng dụng đã đăng ký.");
        }

        if (!"code".equals(request.responseType())) {
            return error(redirect, "unsupported_response_type", "response_type must be code.", request.state());
        }
        if (request.codeChallenge() == null || request.codeChallenge().length() < 43
            || request.codeChallenge().length() > 128 || !"S256".equals(request.codeChallengeMethod())) {
            return error(redirect, "invalid_request", "PKCE with code_challenge_method S256 is required.",
                request.state());
        }
        if (request.scope() != null && !request.scope().isBlank()
            && !Arrays.stream(request.scope().trim().split("\\s+")).allMatch(OAuthCodes.SCOPE::equals)) {
            return error(redirect, "invalid_scope", "The only scope is " + OAuthCodes.SCOPE + ".", request.state());
        }
        if (request.resource() != null && !stripSlash(request.resource()).equals(server.resource())) {
            return error(redirect, "invalid_target", "resource must be " + server.resource() + ".", request.state());
        }
        if (request.state() != null && request.state().length() > 500) {
            return error(redirect, "invalid_request", "state is too long.", null);
        }

        String id = OAuthServer.random("", 24);
        grants.insertRequest(id, client.clientId(), redirect, request.state(), request.codeChallenge(),
            OAuthCodes.SCOPE, OffsetDateTime.now().plus(OAuthCodes.REQUEST_TTL));
        return server.url(OAuthCodes.CONSENT_PAGE) + "?request=" + encode(id);
    }

    /** The request the Cho phép kết nối page shows; missing once it is used or expired. */
    public ConsentRequest read(String requestId) {
        String userId = requireAllowed();
        RequestRow request = grants.findOpenRequest(requestId)
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        ClientRow client = clients.find(request.clientId())
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        return new ConsentRequest(client.name(), URI.create(request.redirectUri()).getHost(),
            (int) OAuthCodes.IDLE_EXPIRY.toDays(), connections.countLive(userId) >= OAuthCodes.MAX_LIVE_CONNECTIONS,
            OAuthCodes.MAX_LIVE_CONNECTIONS);
    }

    /**
     * Cho phép: a new connection and its one-time code, sent back to the client. The connection lives only as long
     * as the code until the code is exchanged, so an approval left unfinished leaves nothing working.
     */
    @Transactional
    public ConsentResult approve(String requestId) {
        String userId = requireAllowed();
        if (grants.findOpenRequest(requestId).isEmpty()) {
            throw new BusinessException(ResponseCode.RESOURCE_NOT_FOUND);
        }
        if (connections.countLive(userId) >= OAuthCodes.MAX_LIVE_CONNECTIONS) {
            throw BusinessException.field(ResponseCode.CONFLICT, "connections", "LIMIT",
                "Bạn đã có " + OAuthCodes.MAX_LIVE_CONNECTIONS + " kết nối trợ lý ngoài còn hiệu lực. "
                    + "Hãy thu hồi bớt rồi kết nối lại.");
        }
        RequestRow request = grants.claimRequest(requestId)
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        ClientRow client = clients.find(request.clientId())
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));

        OffsetDateTime codeExpires = OffsetDateTime.now().plus(OAuthCodes.CODE_TTL);
        long connectionId = connections.insertOAuth(userId, client.name(), client.clientId(), codeExpires);
        String code = OAuthServer.random("", 32);
        grants.insertCode(ExternalConnectionService.hash(code), connectionId, request.redirectUri(),
            request.codeChallenge(), codeExpires);
        audit.record(OAuthAudit.Event.GRANTED, userId, connectionId, client.clientId(), null);

        Map<String, String> params = new LinkedHashMap<>();
        params.put("code", code);
        params.put("state", request.state());
        params.put("iss", server.issuer());
        return new ConsentResult(withParams(request.redirectUri(), params));
    }

    /** Từ chối: the client gets {@code access_denied} and nothing is made. */
    public ConsentResult deny(String requestId) {
        String userId = requireAllowed();
        RequestRow request = grants.claimRequest(requestId)
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        audit.record(OAuthAudit.Event.DENIED, userId, null, request.clientId(), null);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("error", "access_denied");
        params.put("state", request.state());
        params.put("iss", server.issuer());
        return new ConsentResult(withParams(request.redirectUri(), params));
    }

    private String requireAllowed() {
        server.requireEnabled();
        String userId = SecurityContextUtils.getUserId();
        if (!connectionService.mayConnect(userId)) {
            throw new AccessDeniedException("Only roles that may draft courses with the assistant may connect one");
        }
        return userId;
    }

    private String error(String redirect, String error, String description, String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("error", error);
        params.put("error_description", description);
        params.put("state", state);
        params.put("iss", server.issuer());
        return withParams(redirect, params);
    }

    static String withParams(String uri, Map<String, String> params) {
        String query = params.entrySet().stream().filter(entry -> entry.getValue() != null)
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        return uri + (uri.contains("?") ? "&" : "?") + query;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripSlash(String value) {
        return Optional.of(value.strip()).map(v -> v.endsWith("/") ? v.substring(0, v.length() - 1) : v).get();
    }
}
