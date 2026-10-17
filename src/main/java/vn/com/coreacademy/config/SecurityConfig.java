package vn.com.coreacademy.config;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import vn.com.coreacademy.identity.security.UserJwtAuthenticationConverter;
import vn.com.coreacademy.security.ApiSecurityErrorHandler;

@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService details, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(details);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    JwtDecoder decoder(JwtProperties properties) {
        return NimbusJwtDecoder.withSecretKey(key(properties.secret())).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder encoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(properties.secret())));
    }

    private SecretKey key(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET requires at least 32 UTF-8 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, UserJwtAuthenticationConverter converter,
                                    ApiSecurityErrorHandler errors) throws Exception {
        // Cache mặc định do security quản lý; endpoint chỉ đặt header riêng khi có nhu cầu khác.
        http.csrf(csrf -> csrf.disable())
            .headers(headers -> headers.cacheControl(Customizer.withDefaults()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**",
                    "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(errors).accessDeniedHandler(errors)
                .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
            .cors(cors -> {
            });
        return http.build();
    }
}
