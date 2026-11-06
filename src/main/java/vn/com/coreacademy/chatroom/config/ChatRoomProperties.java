package vn.com.coreacademy.chatroom.config;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.chat-room")
public record ChatRoomProperties(
    @NotNull Duration ticketTtl,
    @NotNull Duration idleTimeout,
    @NotNull Duration sessionCheckInterval,
    @NotNull Duration sendTimeout) {

    @AssertTrue(message = "Chat room durations must be positive, check interval must be shorter than idle timeout")
    public boolean isTimingValid() {
        return ticketTtl != null && idleTimeout != null && sessionCheckInterval != null && sendTimeout != null
            && !ticketTtl.isNegative() && !ticketTtl.isZero() && !idleTimeout.isNegative() && !idleTimeout.isZero()
            && !sessionCheckInterval.isNegative() && !sessionCheckInterval.isZero()
            && !sendTimeout.isNegative() && !sendTimeout.isZero() && sendTimeout.toMillis() <= Integer.MAX_VALUE
            && sessionCheckInterval.compareTo(idleTimeout) < 0;
    }
}
