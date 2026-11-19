package vn.com.coreacademy.assistant.external;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.assistant.agent.AssistantAccess;
import vn.com.coreacademy.assistant.drafting.DraftingRoles;
import vn.com.coreacademy.assistant.external.ExternalConnectionRepository.ConnectionRow;
import vn.com.coreacademy.assistant.external.ExternalConnectionViews.ConnectionView;
import vn.com.coreacademy.assistant.external.ExternalConnectionViews.CreateConnection;
import vn.com.coreacademy.assistant.external.ExternalConnectionViews.CreatedConnection;
import vn.com.coreacademy.assistant.external.ExternalConnectionViews.ExternalAssistantStatus;
import vn.com.coreacademy.assistant.external.oauth.OAuthAudit;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.service.AuthService;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.security.SecurityContextUtils;

/**
 * Kết nối trợ lý ngoài (ADR 0004): a user who may use Soạn cùng trợ lý creates, lists and revokes the connections
 * through which a Trợ lý ngoài acts for them. The token is made here, shown once and never stored: only its SHA-256
 * and a display prefix are. With the feature flag off every endpoint answers 404, as if it did not exist.
 */
@Service
@RequiredArgsConstructor
public class ExternalConnectionService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExternalConnectionRepository connections;
    private final ExternalAssistantProperties properties;
    private final AuthService auth;
    private final LearningIdentityService identity;
    private final OAuthAudit audit;

    /** Whether the feature is on and the caller may connect; answers even when it is off, so the page can hide it. */
    public ExternalAssistantStatus status() {
        if (!properties.enabled()) {
            return new ExternalAssistantStatus(false, false);
        }

        return new ExternalAssistantStatus(true, mayConnect(SecurityContextUtils.getUserId()));
    }

    public List<ConnectionView> list() {
        String userId = requireAllowed();
        return connections.listOwn(userId).stream().map(ExternalConnectionService::view).toList();
    }

    /** A new connection for the caller; the response is the only place its token ever appears. */
    public CreatedConnection create(CreateConnection request) {
        String userId = requireAllowed();
        String name = request.name().strip();
        if (!ExternalConnectionCodes.VALID_DAYS.contains(request.validDays())) {
            throw BusinessException.validation("validDays", "INVALID_VALUE",
                "Hạn của kết nối là " + ExternalConnectionCodes.VALID_DAYS_TEXT + " ngày.");
        }

        String token = newToken();
        long id = connections.insert(userId, name, hash(token),
            token.substring(0, ExternalConnectionCodes.DISPLAY_PREFIX_LENGTH),
            OffsetDateTime.now().plusDays(request.validDays()));

        return new CreatedConnection(view(connections.find(id).orElseThrow()), token);
    }

    /** Revokes the caller's own connection at once; someone else's, or one already revoked, answers as missing. */
    public void revoke(long connectionId) {
        String userId = requireAllowed();
        if (!connections.revoke(connectionId, userId)) {
            throw new BusinessException(ResponseCode.RESOURCE_NOT_FOUND);
        }
        connections.find(connectionId).filter(row -> ExternalConnectionRepository.OAUTH.equals(row.kind()))
            .ifPresent(row -> audit.record(OAuthAudit.Event.REVOKED, userId, connectionId, row.clientId(), "user"));
    }

    /** The user may hold a connection now: a role that manages courses and the assistant function. */
    public boolean mayConnect(String userId) {
        return DraftingRoles.mayDraft(identity.actor(userId))
            && auth.me().functions().contains(AssistantAccess.FUNCTION);
    }

    /** The SHA-256 of a token, as stored. */
    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String requireAllowed() {
        if (!properties.enabled()) {
            throw new BusinessException(ResponseCode.NOT_FOUND);
        }
        String userId = SecurityContextUtils.getUserId();
        if (!mayConnect(userId)) {
            throw new AccessDeniedException("Only roles that may draft courses with the assistant may connect one");
        }

        return userId;
    }

    private static String newToken() {
        byte[] random = new byte[ExternalConnectionCodes.TOKEN_RANDOM_BYTES];
        RANDOM.nextBytes(random);

        return ExternalConnectionCodes.TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private static ConnectionView view(ConnectionRow row) {
        return new ConnectionView(row.id(), row.name(), row.kind(), row.tokenPrefix(), row.createTime(),
            row.expiresAt(),
            row.lastUsedAt());
    }
}
