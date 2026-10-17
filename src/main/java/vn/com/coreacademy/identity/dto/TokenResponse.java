package vn.com.coreacademy.identity.dto;

public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
    @Override
    public String toString() {
        return "TokenResponse[accessToken=<redacted>, tokenType=" + tokenType + ", expiresInSeconds="
            + expiresInSeconds + "]";
    }
}
