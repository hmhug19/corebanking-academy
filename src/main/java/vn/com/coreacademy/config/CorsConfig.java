package vn.com.coreacademy.config;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import vn.com.coreacademy.common.constants.HttpHeaderNames;

@Configuration
public class CorsConfig {
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.origins}") String origins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE,
            HttpHeaderNames.REQUEST_ID, HttpHeaders.RANGE, HttpHeaders.IF_RANGE));
        config.setExposedHeaders(List.of(HttpHeaderNames.REQUEST_ID, HttpHeaders.CONTENT_RANGE,
            HttpHeaders.ACCEPT_RANGES, HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.ETAG));

        config.setAllowCredentials(false);

        // Chỉ các route media/session cần cookie; API ghi vẫn bắt buộc Bearer, không xác thực bằng cookie.
        CorsConfiguration media = new CorsConfiguration(config);
        if (media.getAllowedOrigins().contains("*")) {
            throw new IllegalArgumentException("Media cookies require explicit CORS origins");
        }
        media.setAllowCredentials(true);
        media.addAllowedHeader("X-Part-SHA256");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/learning-assets/**", media);
        source.registerCorsConfiguration("/api/v1/me/learning/sessions", media);
        source.registerCorsConfiguration("/api/v1/me/learning/enrollments/*/blocks/*/progress", media);
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
