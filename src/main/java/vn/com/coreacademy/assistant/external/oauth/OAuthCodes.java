package vn.com.coreacademy.assistant.external.oauth;

import java.time.Duration;
import java.util.List;

/**
 * The fixed contract of the OAuth that lets a Trợ lý ngoài connect with one URL (ADR 0005): paths, token shapes and
 * lifetimes, and the redirect URIs a client may register.
 */
public final class OAuthCodes {
    public static final String PROTECTED_RESOURCE_PATH = "/.well-known/oauth-protected-resource";
    public static final String AUTHORIZATION_SERVER_PATH = "/.well-known/oauth-authorization-server";
    public static final String OAUTH_PATH = "/api/v1/oauth";
    public static final String AUTHORIZE_PATH = OAUTH_PATH + "/authorize";
    public static final String TOKEN_PATH = OAUTH_PATH + "/token";
    public static final String REGISTER_PATH = OAUTH_PATH + "/register";
    public static final String REVOKE_PATH = OAUTH_PATH + "/revoke";
    /** The page of the web app where the user agrees or refuses (Cho phép kết nối). */
    public static final String CONSENT_PAGE = "/tro-ly-ngoai/cho-phep";

    /** The one scope: drafting courses with the five tools. */
    public static final String SCOPE = "course_drafting";

    public static final String CLIENT_ID_PREFIX = "chan_cli_";
    public static final String ACCESS_TOKEN_PREFIX = "chan_oat_";
    public static final String REFRESH_TOKEN_PREFIX = "chan_ort_";

    public static final Duration ACCESS_TOKEN_TTL = Duration.ofHours(1);
    /** A connection lapses this long after its last refresh, that is after this long unused. */
    public static final Duration IDLE_EXPIRY = Duration.ofDays(30);
    public static final Duration CODE_TTL = Duration.ofSeconds(60);
    public static final Duration REQUEST_TTL = Duration.ofMinutes(10);
    /** Live connections a user may hold, of both kinds. */
    public static final int MAX_LIVE_CONNECTIONS = 10;

    public static final int MAX_CLIENT_NAME = 100;
    public static final int MAX_REDIRECT_URIS = 5;
    public static final int MAX_REDIRECT_URI = 500;
    /** The name shown when a client registers without one. */
    public static final String UNNAMED_CLIENT = "Trợ lý ngoài";

    /** Claude App's callbacks; Claude Code uses a loopback URI instead. */
    public static final List<String> CLAUDE_CALLBACKS = List.of("https://claude.ai/api/mcp/auth_callback",
        "https://claude.com/api/mcp/auth_callback");

    private OAuthCodes() {
    }
}
