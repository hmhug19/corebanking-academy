package vn.com.coreacademy.scorm.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

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
        http.securityMatcher(request -> request.getLocalPort() == properties.getPlayerPort())
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
