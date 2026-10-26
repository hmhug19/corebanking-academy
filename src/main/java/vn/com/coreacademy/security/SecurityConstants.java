package vn.com.coreacademy.security;

public final class SecurityConstants {
    public static final String JWT_ISSUER = "core-academy";
    public static final String AUTH_VERSION_CLAIM = "av";
    public static final String AUTHENTICATED_AUTHORITY = "USER";
    public static final String TOKEN_TYPE = "Bearer";

    public static final String TOKEN_PURPOSE_CLAIM = "purpose";
    public static final String LEARNING_MEDIA_PURPOSE = "learning_media";
    public static final String MEDIA_SESSION_CLAIM = "mediaSession";
    public static final String MEDIA_FENCE_CLAIM = "mediaFence";

    private SecurityConstants() {
    }
}
