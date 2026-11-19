package vn.com.coreacademy.assistant.external;

import java.util.Set;

/** The fixed contract of a Kết nối trợ lý ngoài: its token's shape, its name and how long it may live. */
public final class ExternalConnectionCodes {
    /** Every token starts with this, so a leaked one is easy to spot and to tell from a sign-in JWT. */
    public static final String TOKEN_PREFIX = "chan_ext_";
    /** An OAuth access token of a connection granted on the Cho phép kết nối page (ADR 0005). */
    public static final String OAUTH_ACCESS_TOKEN_PREFIX = "chan_oat_";
    /** Random bytes after the prefix (base64url, no padding: 43 characters). */
    public static final int TOKEN_RANDOM_BYTES = 32;
    /** How much of the token the list shows to tell connections apart: the prefix and four random characters. */
    public static final int DISPLAY_PREFIX_LENGTH = TOKEN_PREFIX.length() + 4;
    public static final int MAX_NAME = 100;
    public static final Set<Integer> VALID_DAYS = Set.of(30, 90);
    public static final String VALID_DAYS_TEXT = "30 hoặc 90";

    private ExternalConnectionCodes() {
    }
}
