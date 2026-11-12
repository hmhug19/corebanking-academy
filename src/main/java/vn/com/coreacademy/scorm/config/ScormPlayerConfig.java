package vn.com.coreacademy.scorm.config;

import java.util.List;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** The player connector has no LMS authentication/API surface; only scoped launch capabilities are accepted. */
@Configuration
@EnableConfigurationProperties(ScormProperties.class)
public class ScormPlayerConfig {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> scormConnector(ScormProperties properties) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(properties.getPlayerPort());
            factory.addAdditionalTomcatConnectors(connector);
        };
    }

    @Bean
    @Order(0)
    SecurityFilterChain scormSecurity(HttpSecurity http, ScormProperties properties) throws Exception {
        // TLS terminates at Nginx, so the browser's HTTPS player Origin differs from the HTTP connector.
        CorsConfiguration playerCors = new CorsConfiguration();
        playerCors.setAllowedOrigins(List.of(properties.getPlayerOrigin()));
        playerCors.setAllowedMethods(List.of("GET", "HEAD", "POST", "OPTIONS"));
        playerCors.setAllowedHeaders(List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.RANGE, HttpHeaders.IF_RANGE));
        playerCors.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource corsSource = new UrlBasedCorsConfigurationSource();
        corsSource.registerCorsConfiguration("/scorm-player/**", playerCors);
        http.securityMatcher(request -> request.getLocalPort() == properties.getPlayerPort())
            .cors(cors -> cors.configurationSource(corsSource))
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers.frameOptions(frame -> frame.disable()).addHeaderWriter((request, response) -> {
                response.setHeader("Content-Security-Policy", "default-src 'self' data: blob:; "
                    + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; "
                    + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors "
                    + properties.getLmsOrigin() + " 'self'");
                response.setHeader("Referrer-Policy", "no-referrer");
                response.setHeader("X-Content-Type-Options", "nosniff");
            }))
            .authorizeHttpRequests(auth -> auth.requestMatchers("/scorm-player/**").permitAll().anyRequest().denyAll());
        return http.build();
    }
}
