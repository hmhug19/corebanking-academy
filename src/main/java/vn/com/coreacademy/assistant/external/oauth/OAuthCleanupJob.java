package vn.com.coreacademy.assistant.external.oauth;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assistant.external.ExternalAssistantProperties;
import vn.com.coreacademy.assistant.external.ExternalConnectionRepository;

/**
 * Removes what the anonymous OAuth endpoints leave behind once nothing can use it: requests and codes a day after they
 * expired, approvals whose code was never exchanged, and registrations a week old that nobody connected with. A
 * connection that ever worked is kept, revoked or not, so the user's history and the audit trail stay whole.
 */
@Log4j2
@Component
public class OAuthCleanupJob {
    static final Duration GRACE = Duration.ofDays(1);
    static final Duration UNUSED_CLIENT_AGE = Duration.ofDays(7);

    private final ExternalAssistantProperties properties;
    private final OAuthGrantRepository grants;
    private final OAuthClientRepository clients;
    private final ExternalConnectionRepository connections;
    private final Clock clock;

    @Autowired
    public OAuthCleanupJob(ExternalAssistantProperties properties, OAuthGrantRepository grants,
                           OAuthClientRepository clients, ExternalConnectionRepository connections) {
        this(properties, grants, clients, connections, Clock.systemUTC());
    }

    OAuthCleanupJob(ExternalAssistantProperties properties, OAuthGrantRepository grants, OAuthClientRepository clients,
                    ExternalConnectionRepository connections, Clock clock) {
        this.properties = properties;
        this.grants = grants;
        this.clients = clients;
        this.connections = connections;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.assistant.external.oauth-cleanup-cron:0 40 3 * * *}")
    @Transactional
    public void purge() {
        if (!properties.enabled()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        int grantsRemoved = grants.deleteExpired(now.minus(GRACE));
        int connectionsRemoved = connections.deleteUnexchangedOAuth(now.minus(GRACE));
        int clientsRemoved = clients.deleteUnused(now.minus(UNUSED_CLIENT_AGE));
        log.info("External assistant OAuth cleanup requestsAndCodes={} unexchangedConnections={} unusedClients={}",
            grantsRemoved, connectionsRemoved, clientsRemoved);
    }
}
