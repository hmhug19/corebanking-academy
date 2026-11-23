package vn.com.coreacademy.assistant.external.oauth;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.log4j.Log4j2;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.assistant.external.ExternalAssistantProperties;
import vn.com.coreacademy.assistant.external.oauth.OAuthClientRepository.ClientRow;
import vn.com.coreacademy.assistant.external.oauth.OAuthRegistrationController.Registration;

/** Registers OAuth clients (RFC 7591), allowing only Claude's callbacks and loopback redirect URIs. */
@Log4j2
@Service
public class OAuthClientService {
    private static final List<String> GRANT_TYPES = List.of("authorization_code", "refresh_token");

    /** The key under which every registration counts toward the instance-wide ceiling. */
    private static final String ALL_ADDRESSES = "*";

    private final OAuthClientRepository clients;
    private final OAuthServer server;
    private final OAuthAudit audit;
    private final PerMinuteLimiter limiter;
    private final PerMinuteLimiter total;

    public OAuthClientService(OAuthClientRepository clients, OAuthServer server, OAuthAudit audit,
                              ExternalAssistantProperties properties) {
        this.clients = clients;
        this.server = server;
        this.audit = audit;
        this.limiter = new PerMinuteLimiter(properties.oauthRegistrationsPerMinute(), Clock.systemUTC());
        this.total = new PerMinuteLimiter(properties.oauthRegistrationsPerMinuteTotal(), Clock.systemUTC());
    }

    /** The new client's registration response; refuses what this server cannot honour. */
    public Map<String, Object> register(Registration registration, String address) {
        server.requireEnabled();
        if (!limiter.admit(address) || !total.admit(ALL_ADDRESSES)) {
            log.warn("Refused OAuth client registration over the rate limit");
            throw new OAuthException(HttpStatus.TOO_MANY_REQUESTS, "slow_down",
                "Too many registrations. Try again in a minute.");
        }
        List<String> uris = registration.redirectUris();
        if (uris == null || uris.isEmpty() || uris.size() > OAuthCodes.MAX_REDIRECT_URIS) {
            throw OAuthException.badRequest("invalid_redirect_uri",
                "redirect_uris must list 1 to " + OAuthCodes.MAX_REDIRECT_URIS + " URIs.");
        }
        for (String uri : uris) {
            if (!OAuthRedirects.allowed(uri)) {
                audit.record(OAuthAudit.Event.REGISTRATION_REFUSED, null, null, null, "redirect_uri");
                throw OAuthException.badRequest("invalid_redirect_uri", "Only Claude's callback URIs and "
                    + "http://localhost or http://127.0.0.1 redirect URIs are allowed.");
            }
        }
        String method = registration.tokenEndpointAuthMethod();
        if (method != null && !"none".equals(method)) {
            throw OAuthException.badRequest("invalid_client_metadata",
                "Only public clients are supported: token_endpoint_auth_method must be none.");
        }
        if (registration.grantTypes() != null && !GRANT_TYPES.containsAll(registration.grantTypes())) {
            throw OAuthException.badRequest("invalid_client_metadata",
                "grant_types may only be authorization_code and refresh_token.");
        }
        if (registration.responseTypes() != null && !List.of("code").containsAll(registration.responseTypes())) {
            throw OAuthException.badRequest("invalid_client_metadata", "response_types may only be code.");
        }

        ClientRow client = clients.insert(OAuthServer.random(OAuthCodes.CLIENT_ID_PREFIX, 16), name(registration),
            List.copyOf(uris));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", client.clientId());
        body.put("client_id_issued_at", client.createTime().toEpochSecond());
        body.put("client_name", client.name());
        body.put("redirect_uris", client.redirectUris());
        body.put("grant_types", GRANT_TYPES);
        body.put("response_types", List.of("code"));
        body.put("token_endpoint_auth_method", "none");
        body.put("scope", OAuthCodes.SCOPE);
        return body;
    }

    /**
     * The self-declared name without control or format characters (bidi overrides, line breaks), cut to
     * {@link OAuthCodes#MAX_CLIENT_NAME} code points so a surrogate pair is never split.
     */
    static String name(Registration registration) {
        String raw = registration.clientName() == null ? "" : registration.clientName();
        String name = raw.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", " ").replaceAll("\\s+", " ").strip();
        if (name.isEmpty()) {
            return OAuthCodes.UNNAMED_CLIENT;
        }
        return name.codePointCount(0, name.length()) > OAuthCodes.MAX_CLIENT_NAME
            ? name.substring(0, name.offsetByCodePoints(0, OAuthCodes.MAX_CLIENT_NAME)).strip()
            : name;
    }
}
