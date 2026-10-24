package vn.com.coreacademy.assistant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns scheduling on for the pending-action sweep, so it does not depend on another module enabling it. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AssistantSchedulingConfig {
}
