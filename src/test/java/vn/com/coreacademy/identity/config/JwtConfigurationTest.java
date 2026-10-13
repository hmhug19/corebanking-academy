package vn.com.coreacademy.identity.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JwtConfigurationTest {
    @TempDir Path directory;

    @Test
    void importsLocalEnvFileAndResolvesJwtSecret() throws Exception {
        String secret = "configuration-test-secret-at-least-32-bytes";
        Path env = directory.resolve(".env");
        Files.writeString(env, "JWT_SECRET=" + secret + "\n");
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(JwtConfiguration.class)
                .withPropertyValues("spring.config.import=optional:file:" + env.toUri().getPath() + "[.properties]")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwtProperties.class).secret()).isEqualTo(secret);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtConfiguration {}
}
