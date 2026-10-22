package vn.com.coreacademy.knowledge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Turns scheduling on and bounds the ingestion workers; the worker class decides what runs on them. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class KnowledgeSchedulingConfig {
    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor knowledgeWorkerExecutor(KnowledgeProperties properties) {
        int workers = properties.worker().concurrency();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        // No queue: the worker only submits when it holds a free slot, so a job never waits behind another.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("kb-worker-");
        return executor;
    }
}
