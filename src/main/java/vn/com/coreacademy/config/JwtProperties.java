package vn.com.coreacademy.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@NotBlank String secret, @Min(1) long expiryMinutes) {
    // Never expose the signing secret through configuration logging.
    @Override
    public String toString() {
        return "JwtProperties[secret=<redacted>, expiryMinutes=" + expiryMinutes + "]";
    }
}
