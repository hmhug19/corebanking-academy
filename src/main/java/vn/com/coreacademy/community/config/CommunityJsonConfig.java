package vn.com.coreacademy.community.config;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Không thay cách đọc DTO cũ; riêng Community từ chối field ngoài whitelist, kể cả ở object lồng nhau. */
@Configuration(proxyBeanMethods = false)
public class CommunityJsonConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer communityUnknownFields() {
        return builder -> builder.postConfigurer(mapper -> mapper.addHandler(new DeserializationProblemHandler() {
            @Override
            public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
                                                 JsonDeserializer<?> deserializer, Object bean, String name)
                throws IOException {
                Class<?> type = bean instanceof Class<?> clazz ? clazz : bean.getClass();
                if (type.getPackageName().startsWith("vn.com.coreacademy.community.dto")) {
                    throw UnrecognizedPropertyException.from(parser, bean, name,
                        deserializer.getKnownPropertyNames());
                }
                return false;
            }
        }));
    }
}
