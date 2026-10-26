package vn.com.coreacademy.learning.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class LearningMediaConfig {
    @Bean(destroyMethod = "shutdown")
    ExecutorService learningMediaExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "learning-media-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
