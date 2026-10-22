package vn.com.coreacademy.knowledge.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KnowledgeProperties.class)
public class KnowledgeConfig {
}
