package vn.com.coreacademy.assistant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import vn.com.coreacademy.assistant.conversation.ConversationRetention;
import vn.com.coreacademy.assistant.external.ExternalAssistantProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AssistantProperties.class, AssistantLimits.class, ConversationRetention.class,
    AssistantModels.class, GenerationProperties.class, AssistantBudget.class, ExternalAssistantProperties.class})
public class AssistantConfig {
}
