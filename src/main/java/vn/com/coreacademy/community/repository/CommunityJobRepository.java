package vn.com.coreacademy.community.repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.entity.CommunityOutboxEvent;

@Repository
@RequiredArgsConstructor
public class CommunityJobRepository {
    private final CommunityRepository repository;

    public List<CommunityOutboxEvent> dedup(String key) {
        return repository.list("select e from CommunityOutboxEvent e where e.dedupKey = :key",
            CommunityOutboxEvent.class, Map.of("key", key));
    }

    public List<CommunityOutboxEvent> pending() {
        return repository.page("""
            select e from CommunityOutboxEvent e where e.eventStatus in ('pending', 'processing')
              and e.availableAt <= :now and (e.leaseExpiresAt is null or e.leaseExpiresAt <= :now)
            order by e.eventId
            """, CommunityOutboxEvent.class, Map.of("now", Instant.now()), 0, 1);
    }
}
