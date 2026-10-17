package vn.com.coreacademy.identity.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DevBootstrap {
    @Bean
    CommandLineRunner bootstrapAdmin(BootstrapAdminService bootstrap,
                                     @Value("${app.bootstrap.admin-id:}") String userId,
                                     @Value("${app.bootstrap.admin-user:}") String username,
                                     @Value("${app.bootstrap.admin-password:}") String password) {
        return args -> bootstrap.create(userId, username, password);
    }
}
