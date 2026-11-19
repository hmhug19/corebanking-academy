package vn.com.coreacademy.assistant.external.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Set;

/**
 * Which redirect URIs a client may register, and which requested URI matches a registered one. Only Claude App's
 * callbacks and loopback URIs on the user's machine (RFC 8252) are allowed, so no web site can receive a user's code.
 */
final class OAuthRedirects {
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private OAuthRedirects() {
    }

    /** A URI a client may register: one of Claude's callbacks, or http on a loopback host with any port and path. */
    static boolean allowed(String uri) {
        return uri != null && (OAuthCodes.CLAUDE_CALLBACKS.contains(uri) || loopback(uri) != null);
    }

    /**
     * Whether a requested URI is a registered one: equal, or for a loopback URI equal but for the port, which the
     * client picks when it starts listening (RFC 8252 §7.3).
     */
    static boolean matches(String registered, String requested) {
        if (registered.equals(requested)) {
            return true;
        }
        URI left = loopback(registered);
        URI right = loopback(requested);
        return left != null && right != null && left.getHost().equals(right.getHost())
            && Objects.equals(left.getRawPath(), right.getRawPath())
            && Objects.equals(left.getRawQuery(), right.getRawQuery());
    }

    private static URI loopback(String value) {
        if (value == null || value.length() > OAuthCodes.MAX_REDIRECT_URI) {
            return null;
        }
        try {
            URI uri = new URI(value);
            boolean ok = "http".equals(uri.getScheme()) && uri.getHost() != null
                && LOOPBACK_HOSTS.contains(uri.getHost()) && uri.getRawUserInfo() == null
                && uri.getRawFragment() == null;
            return ok ? uri : null;
        } catch (URISyntaxException exception) {
            return null;
        }
    }
}
