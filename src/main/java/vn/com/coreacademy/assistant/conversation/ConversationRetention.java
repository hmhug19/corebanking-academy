package vn.com.coreacademy.assistant.conversation;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** How long a conversation may sit untouched before the nightly job deletes it (compliance sets the value). */
@Validated
@ConfigurationProperties("app.assistant.history")
public record ConversationRetention(@NotNull Duration retention) {
}
