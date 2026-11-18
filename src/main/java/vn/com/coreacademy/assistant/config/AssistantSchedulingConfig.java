package vn.com.coreacademy.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Turns scheduling on for the pending-action sweep, so it does not depend on another module enabling it. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AssistantSchedulingConfig {

    /**
     * One thread for course drafting, so its model calls never hold the shared scheduler thread. One is enough: the
     * worker already gives way to the chat room, and the local model answers one call at a time.
     */
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor draftingWorkerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("drafting-worker-");
        return executor;
    }
}
