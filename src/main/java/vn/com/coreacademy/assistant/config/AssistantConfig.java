package vn.com.coreacademy.assistant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AssistantProperties.class, AssistantLimits.class})
public class AssistantConfig {
}
