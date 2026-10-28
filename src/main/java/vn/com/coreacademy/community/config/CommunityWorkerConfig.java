package vn.com.coreacademy.community.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommunityWorkerConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService communityJobsExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "community-jobs-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService communityMediaExecutor() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "community-media-worker");
            thread.setDaemon(true);
            return thread;
        });
    }
}
