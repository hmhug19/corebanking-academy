package vn.com.coreacademy.assistant.external.oauth;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.external.ExternalAssistantProperties;

/**
 * The address the OAuth limits count by. Behind the web nginx every request comes from the proxy, so its
 * {@code X-Real-IP} (which nginx sets to the peer it saw, overwriting whatever the client sent) is the caller; from
 * any peer that is not a trusted proxy the header is ignored, so a client cannot pick its own bucket.
 */
@Component
public class OAuthClientAddress {
    static final String REAL_IP_HEADER = "X-Real-IP";

    private final List<IpAddressMatcher> proxies;

    public OAuthClientAddress(ExternalAssistantProperties properties) {
        this.proxies = properties.oauthTrustedProxies().stream().map(String::strip).filter(cidr -> !cidr.isEmpty())
            .map(IpAddressMatcher::new).toList();
    }

    public String of(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String forwarded = request.getHeader(REAL_IP_HEADER);
        if (forwarded == null || forwarded.isBlank() || proxies.stream().noneMatch(proxy -> proxy.matches(peer))) {
            return peer;
        }
        return forwarded.strip();
    }
}
