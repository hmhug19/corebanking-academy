package vn.com.coreacademy.assistant.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import vn.com.coreacademy.identity.security.UserJwtAuthenticationConverter;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.security.ApiSecurityErrorHandler;

/**
 * The security chain of {@code /api/v1/mcp}, ordered before the main one: only a Kết nối trợ lý ngoài token
 * authenticates here ({@link ExternalTokenFilter}); a sign-in JWT does not, since this chain has no resource server.
 * The main chain in turn refuses connection tokens on every other path, because they are not JWTs its decoder accepts.
 */
@Configuration(proxyBeanMethods = false)
public class ExternalMcpSecurityConfig {
    public static final String MCP_PATH = "/api/v1/mcp";

    @Bean
    @Order(1)
    SecurityFilterChain externalMcpFilterChain(HttpSecurity http, ExternalAssistantProperties properties,
                                               ExternalConnectionRepository connections,
                                               ExternalConnectionService service, LearningIdentityService identity,
                                               UserJwtAuthenticationConverter converter, ObjectMapper mapper,
                                               ApiSecurityErrorHandler errors, ExternalCallLimiter limiter)
        throws Exception {
        http.securityMatcher(MCP_PATH, MCP_PATH + "/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new ExternalTokenFilter(properties, connections, service, identity, converter, mapper,
                limiter),
                AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errors).accessDeniedHandler(errors));
        return http.build();
    }
}
