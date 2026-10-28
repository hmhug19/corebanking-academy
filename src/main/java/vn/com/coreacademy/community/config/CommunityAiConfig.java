package vn.com.coreacademy.community.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vn.com.coreacademy.assistant.config.AssistantProperties;

@Configuration
public class CommunityAiConfig {
    @Bean(destroyMethod = "shutdown")
    public ExecutorService communityAiExecutor(AssistantProperties properties) {
        return new ThreadPoolExecutor(properties.maxConcurrentTurns(), properties.maxConcurrentTurns(), 0,
            TimeUnit.SECONDS, new SynchronousQueue<>(), action -> {
                Thread thread = new Thread(action, "community-ai");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    }
}
