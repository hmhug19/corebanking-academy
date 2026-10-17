package vn.com.coreacademy.security;

public final class SecurityConstants {
    public static final String JWT_ISSUER = "core-academy";
    public static final String AUTH_VERSION_CLAIM = "av";
    public static final String AUTHENTICATED_AUTHORITY = "USER";
    public static final String TOKEN_TYPE = "Bearer";

    private SecurityConstants() {
    }
}
