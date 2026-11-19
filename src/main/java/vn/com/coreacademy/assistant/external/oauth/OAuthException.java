package vn.com.coreacademy.assistant.external.oauth;

import org.springframework.http.HttpStatus;

/** An OAuth error answered as RFC 6749 §5.2 / RFC 7591 §3.2.2 JSON: {@code error} and {@code error_description}. */
public class OAuthException extends RuntimeException {
    private final HttpStatus status;
    private final String error;

    public OAuthException(HttpStatus status, String error, String description) {
        super(description);
        this.status = status;
        this.error = error;
    }

    public static OAuthException badRequest(String error, String description) {
        return new OAuthException(HttpStatus.BAD_REQUEST, error, description);
    }

    public HttpStatus status() {
        return status;
    }

    public String error() {
        return error;
    }
}
