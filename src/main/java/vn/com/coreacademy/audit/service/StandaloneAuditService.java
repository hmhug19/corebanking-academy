package vn.com.coreacademy.audit.service;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.entity.AuditLog;
import vn.com.coreacademy.audit.repository.AuditLogRepository;

/**
 * Audit records of calls rather than of data changes: written in a transaction of their own, so a refused or failed
 * call is recorded too and a failing record never undoes the call. {@code details} holds selected fields only, never
 * passwords, hashes, tokens or whole payloads.
 */
@Service
@RequiredArgsConstructor
public class StandaloneAuditService {
    private final AuditLogRepository logs;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actor, String action, String entityType, String entityId, Map<String, Object> details) {
        AuditLog event = new AuditLog();
        event.setActorId(actor);
        event.setAction(action);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setDetails(new LinkedHashMap<>(details));
        logs.save(event);
    }
}
