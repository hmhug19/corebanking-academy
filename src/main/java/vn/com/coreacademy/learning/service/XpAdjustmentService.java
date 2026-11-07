package vn.com.coreacademy.learning.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.constants.LearningCodes.XpSource;
import vn.com.coreacademy.learning.dto.GamificationConfig;
import vn.com.coreacademy.learning.dto.XpAdjustmentDtos.AdjustXp;
import vn.com.coreacademy.learning.dto.XpAdjustmentDtos.XpAdjustmentResult;
import vn.com.coreacademy.learning.repository.XpLedgerRepository;

/**
 * Sửa XP của một học viên khi có gian lận hoặc lỗi dữ liệu. Chỉ quản trị hệ thống (ADMIN hoặc SUPER_ADMIN, phạm vi
 * GLOBAL). Sửa bằng một dòng bù trừ ADJUSTMENT có lý do bắt buộc, không bao giờ sửa hay xóa dòng cũ, và ghi audit cùng
 * giao dịch với dòng sổ.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class XpAdjustmentService {
    static final int MAX_ABS_AMOUNT = 100_000;

    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final XpLedgerService ledger;
    private final XpLedgerRepository repository;
    private final LearningPointsService points;
    private final IdentityAuditService audit;

    /** Quản trị hệ thống: ADMIN hoặc SUPER_ADMIN với phạm vi GLOBAL. */
    public static boolean isSystemAdmin(Actor actor) {
        return actor.grants().stream().anyMatch(grant -> grant.scope().type() == ScopeType.GLOBAL
            && (RoleCodes.ADMIN.equals(grant.roleCode()) || RoleCodes.SUPER_ADMIN.equals(grant.roleCode())));
    }

    public XpAdjustmentResult adjust(AdjustXp request) {
        Actor actor = access.actor();
        if (!isSystemAdmin(actor)) {
            throw new AccessDeniedException("System admin required");
        }
        String reason = request.reason().strip();
        if (reason.isEmpty()) {
            throw BusinessException.validation("reason", "REQUIRED", "Cần nhập lý do điều chỉnh.");
        }
        int amount = request.amount();
        if (amount == 0 || Math.abs(amount) > MAX_ABS_AMOUNT) {
            throw BusinessException.validation("amount", "INVALID_VALUE",
                "Số XP phải khác 0 và không quá " + MAX_ABS_AMOUNT + ".");
        }
        var learner = identity.activePerson(request.userId());
        var current = points.scores(List.of(learner.id())).get(learner.id());
        if (current.total() + amount < 0) {
            throw BusinessException.validation("amount", "INVALID_VALUE",
                "Điều chỉnh không được làm tổng XP của học viên âm.");
        }
        GamificationConfig config = ledger.currentConfig();
        String ref = UUID.randomUUID().toString();
        long entryId = repository.appendAdjustment(learner.id(), amount, Instant.now(), ref, config.versionId(),
            reason);
        audit.record(actor.person().id(), "XP_ADJUSTED", "xp_ledger", String.valueOf(entryId), learner.orgId(),
            Map.of("userId", learner.id(), "amount", amount, "reason", reason, "sourceRef", ref,
                "source", XpSource.ADJUSTMENT));
        return new XpAdjustmentResult(entryId, learner.id(), amount, current.total() + amount);
    }
}
