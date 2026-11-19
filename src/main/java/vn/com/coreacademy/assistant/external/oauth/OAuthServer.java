package vn.com.coreacademy.assistant.external.oauth;

import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.external.ExternalAssistantProperties;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;

/**
 * Where the OAuth of a Trợ lý ngoài lives: the issuer is the web app's origin ({@code app-base-url}), which serves
 * the API and {@code /.well-known} through its reverse proxy, and the protected resource is its MCP URL.
 */
@Component
@RequiredArgsConstructor
public class OAuthServer {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ExternalAssistantProperties properties;

    /** Every OAuth endpoint answers 404 while the Trợ lý ngoài is off. */
    public void requireEnabled() {
        if (!properties.enabled()) {
            throw new BusinessException(ResponseCode.NOT_FOUND);
        }
    }

    public String issuer() {
        return properties.origin();
    }

    public String url(String path) {
        return issuer() + path;
    }

    /** The MCP endpoint's URL, the one resource tokens are for. */
    public String resource() {
        return url("/api/v1/mcp");
    }

    /** A random value of {@code bytes} bytes, base64url without padding, after the prefix. */
    public static String random(String prefix, int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
