package vn.com.coreacademy.identity.dto;



public record TokenResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
