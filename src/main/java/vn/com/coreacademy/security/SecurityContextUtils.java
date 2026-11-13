package vn.com.coreacademy.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Đọc thông tin cơ bản từ JWT trong context đã xác thực, không decode lại token hoặc truy vấn hồ sơ.
 * Context gắn với luồng hiện tại; job/async phải có context tin cậy trước khi dùng getter.
 */
public final class SecurityContextUtils {
    private SecurityContextUtils() {
    }

    /**
     * Kiểm tra context có JWT đã xác thực; không kiểm tra lại trạng thái tài khoản trong database.
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken && authentication.isAuthenticated();
    }

    /**
     * Lấy mã cán bộ từ sub, giữ nguyên số 0 đầu; từ chối context hoặc subject không hợp lệ.
     *
     * @throws BadCredentialsException nếu thiếu JWT đã xác thực hoặc mã cán bộ không hợp lệ
     */
    public static String getUserId() {
        String userId = requireJwt().getSubject();
        if (userId == null || userId.isBlank() || userId.length() > 20 || !userId.equals(userId.trim())) {
            throw new BadCredentialsException("Invalid subject");
        }
        return userId;
    }

    /**
     * Lấy av của token hiện tại để logout đối chiếu đúng phiên bản, tránh thu hồi nhầm phiên mới hơn.
     *
     * @throws BadCredentialsException nếu thiếu JWT đã xác thực hoặc av không phải số không âm
     */
    public static long getAuthorizationVersion() {
        Object version = requireJwt().getClaims().get(SecurityConstants.AUTH_VERSION_CLAIM);
        if (!(version instanceof Number number) || number.longValue() < 0) {
            throw new BadCredentialsException("Invalid authorization version");
        }
        return number.longValue();
    }

    /** The session id of the current token; null for a token issued before sessions had one. */
    public static String getSessionId() {
        return requireJwt().getClaimAsString(SecurityConstants.SESSION_ID_CLAIM);
    }

    /** When the current token expires. */
    public static java.time.Instant getExpiresAt() {
        return requireJwt().getExpiresAt();
    }

    private static Jwt requireJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
            || !authentication.isAuthenticated()) {
            throw new BadCredentialsException("Authenticated JWT required");
        }
        return jwtAuthentication.getToken();
    }
}
