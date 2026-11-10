package vn.com.coreacademy.assistant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import vn.com.coreacademy.assistant.conversation.ConversationRetention;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AssistantProperties.class, AssistantLimits.class, ConversationRetention.class})
public class AssistantConfig {
}
