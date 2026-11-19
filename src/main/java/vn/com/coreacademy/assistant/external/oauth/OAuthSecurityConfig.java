package vn.com.coreacademy.assistant.external.oauth;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The security chain of OAuth discovery and of the OAuth endpoints a client calls ({@code /api/v1/oauth/**}): public,
 * stateless, no CSRF and no resource server, so a bearer header is ignored here. The Cho phép kết nối API is not on
 * it: it lives under {@code /api/v1/assistant/} and keeps the main chain's sign-in JWT. Any origin may call these
 * endpoints without credentials, as browser-based MCP clients do.
 */
@Configuration(proxyBeanMethods = false)
public class OAuthSecurityConfig {
    @Bean
    @Order(2)
    SecurityFilterChain oauthFilterChain(HttpSecurity http) throws Exception {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("*"));
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type", "Authorization", "MCP-Protocol-Version"));
        cors.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);

        http.securityMatcher(OAuthCodes.PROTECTED_RESOURCE_PATH, OAuthCodes.PROTECTED_RESOURCE_PATH + "/**",
                OAuthCodes.AUTHORIZATION_SERVER_PATH, OAuthCodes.AUTHORIZATION_SERVER_PATH + "/**",
                OAuthCodes.OAUTH_PATH + "/**")
            .csrf(csrf -> csrf.disable())
            .cors(configurer -> configurer.configurationSource(source))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
