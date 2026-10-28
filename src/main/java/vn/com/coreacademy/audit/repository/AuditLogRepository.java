package vn.com.coreacademy.audit.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.com.coreacademy.audit.entity.AuditLog;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    boolean existsByActorIdAndEntityTypeAndEntityIdAndAction(String actorId, String entityType, String entityId,
                                                            String action);
}
