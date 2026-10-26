package vn.com.coreacademy.security;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/** Cookie chỉ được đọc trên GET/HEAD media, không mở cookie authentication cho các API ghi. */
public final class MediaBearerTokenResolver implements BearerTokenResolver {
    public static final String COOKIE = "academy_media";
    private final DefaultBearerTokenResolver bearer = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String token = bearer.resolve(request);
        if (token != null) {
            return token;
        }
        if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
            && request.getRequestURI().matches("/api/v1/learning-assets/[0-9]+/(content|gateway-authorization)")
            && request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if (COOKIE.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
