package vn.com.coreacademy.assistant.conversation;

import java.time.Clock;
import java.time.Instant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes conversations nobody has touched for {@code app.assistant.history.retention}; nothing else is removed. */
@Slf4j
@Component
public class ConversationRetentionJob {
    private final ConversationService conversations;
    private final ConversationRetention retention;
    private final Clock clock;

    @Autowired
    public ConversationRetentionJob(ConversationService conversations, ConversationRetention retention) {
        this(conversations, retention, Clock.systemUTC());
    }

    ConversationRetentionJob(ConversationService conversations, ConversationRetention retention, Clock clock) {
        this.conversations = conversations;
        this.retention = retention;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.assistant.history.cleanup-cron:0 30 3 * * *}")
    public void purge() {
        Instant cutoff = clock.instant().minus(retention.retention());
        int removed = conversations.deleteInactiveBefore(cutoff);
        log.info("Assistant history retention removed={} cutoff={}", removed, cutoff);
    }
}
