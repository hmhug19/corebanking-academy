package vn.com.coreacademy.audit.service;

import java.util.LinkedHashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.entity.AuditLog;
import vn.com.coreacademy.audit.repository.AuditLogRepository;

@Service
@RequiredArgsConstructor
public class IdentityAuditService {
    private final AuditLogRepository logs;

    /**
     * Ghi audit trong transaction nghiệp vụ hiện có để cùng commit hoặc rollback với dữ liệu thay đổi. changes
     * chỉ chứa trường đã chọn lọc, không đưa mật khẩu/hash/token hoặc toàn bộ payload vào audit; orgId được bổ
     * sung vào details.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String actor, String action, String entityType, String entityId, Long orgId,
                       Map<String, Object> changes) {
        AuditLog event = new AuditLog();
        event.setActorId(actor);
        event.setAction(action);
        event.setEntityType(entityType);
        event.setEntityId(entityId);

        Map<String, Object> details = new LinkedHashMap<>(changes);
        details.put("orgId", orgId);
        event.setDetails(details);

        logs.save(event);
    }
}
