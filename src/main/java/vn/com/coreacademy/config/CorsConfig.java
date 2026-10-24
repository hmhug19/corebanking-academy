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

        // Xác thực dùng Bearer header, không dùng cookie; frontend vẫn đọc được X-Request-Id để tra lỗi.
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
