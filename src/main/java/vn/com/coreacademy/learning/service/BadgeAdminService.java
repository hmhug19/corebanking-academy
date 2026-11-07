package vn.com.coreacademy.learning.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.constants.LearningCodes.BadgeRule;
import vn.com.coreacademy.learning.constants.LearningCodes.BadgeStatus;
import vn.com.coreacademy.learning.dto.BadgeAdminDtos.AdminBadgeView;
import vn.com.coreacademy.learning.dto.BadgeAdminDtos.SaveBadge;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.BadgeRepository;
import vn.com.coreacademy.learning.repository.BadgeRepository.AdminRow;
import vn.com.coreacademy.learning.repository.BadgeRepository.Definition;

/**
 * Quản lý huy hiệu của quản trị hệ thống: xem, tạo, sửa và ngừng dùng. Tạo huy hiệu trả về ngay, việc trao bù chạy ở
 * nền và tiến độ nằm trên huy hiệu. Khi đã có người nhận, luật bị khóa (chỉ sửa được tên, mô tả, biểu tượng). Ngừng
 * dùng không thu hồi huy hiệu đã trao. Mọi thay đổi ghi audit cùng giao dịch.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class BadgeAdminService {
    private static final Pattern ICON = Pattern.compile("^[a-z0-9-]{1,50}$");
    private static final int MAX_N = 1000;

    private final LearningAccessPolicy access;
    private final BadgeRepository repository;
    private final BadgeService badges;
    private final BadgeBackfillJob backfill;
    private final IdentityAuditService audit;

    public List<AdminBadgeView> list() {
        requireSystemAdmin();
        badges.ensureStarterSet();
        return repository.adminRows().stream().map(this::view).toList();
    }

    public AdminBadgeView create(SaveBadge request) {
        Actor actor = requireSystemAdmin();
        badges.ensureStarterSet();
        Rule rule = validate(request);
        String code = "CUSTOM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        long id = repository.insertDefinition(code, rule.name(), rule.description(), rule.icon(), rule.type(),
            rule.n(), rule.programId(), repository.nextSortNo(), actor.person().id()).orElseThrow();
        repository.setBackfill(id, "RUNNING", 0, 0);
        audit.record(actor.person().id(), "BADGE_CREATED", "badge", String.valueOf(id), null, changes(rule));
        backfill.startAfterCommit(id);
        return view(repository.adminRow(id).orElseThrow());
    }

    public AdminBadgeView update(long badgeId, SaveBadge request) {
        Actor actor = requireSystemAdmin();
        AdminRow row = repository.adminRow(badgeId).orElseThrow(() -> new BusinessException(LearningResponseCode.NOT_FOUND));
        Definition current = row.definition();
        Rule rule = validate(request);
        boolean ruleChanged = !current.ruleType().equals(rule.type()) || !Objects.equals(current.paramN(), rule.n())
            || !Objects.equals(current.paramProgramId(), rule.programId());
        if (ruleChanged && row.holders() > 0) {
            throw BusinessException.validation("ruleType", "LOCKED",
                "Huy hiệu đã có người nhận nên không đổi được luật.");
        }
        repository.updateDefinition(badgeId, rule.name(), rule.description(), rule.icon(), rule.type(), rule.n(),
            rule.programId());
        if (ruleChanged && BadgeStatus.ACTIVE.equals(current.status())) {
            repository.setBackfill(badgeId, "RUNNING", 0, 0);
            backfill.startAfterCommit(badgeId);
        }
        audit.record(actor.person().id(), "BADGE_UPDATED", "badge", String.valueOf(badgeId), null, changes(rule));
        return view(repository.adminRow(badgeId).orElseThrow());
    }

    /** Ngừng dùng: không còn trao nữa; người đã nhận giữ huy hiệu. Gọi lại là không đổi. */
    public AdminBadgeView retire(long badgeId) {
        Actor actor = requireSystemAdmin();
        AdminRow row = repository.adminRow(badgeId).orElseThrow(() -> new BusinessException(LearningResponseCode.NOT_FOUND));
        if (!BadgeStatus.RETIRED.equals(row.definition().status())) {
            repository.setStatus(badgeId, BadgeStatus.RETIRED);
            audit.record(actor.person().id(), "BADGE_RETIRED", "badge", String.valueOf(badgeId), null,
                Map.of("holders", row.holders()));
        }
        return view(repository.adminRow(badgeId).orElseThrow());
    }

    private record Rule(String name, String description, String icon, String type, Integer n, Long programId) {
    }

    private Rule validate(SaveBadge request) {
        String name = request.name() == null ? "" : request.name().strip();
        String description = request.description() == null ? "" : request.description().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw BusinessException.validation("name", "INVALID_VALUE", "Tên huy hiệu từ 1 đến 100 ký tự.");
        }
        if (description.isEmpty() || description.length() > 300) {
            throw BusinessException.validation("description", "INVALID_VALUE", "Mô tả từ 1 đến 300 ký tự.");
        }
        String icon = request.icon() == null ? "" : request.icon().strip();
        if (!ICON.matcher(icon).matches()) {
            throw BusinessException.validation("icon", "INVALID_VALUE",
                "Biểu tượng là tên Lucide viết thường, số và dấu gạch ngang.");
        }
        String type = request.ruleType();
        if (!BadgeRule.ALL.contains(type)) {
            throw BusinessException.validation("ruleType", "INVALID_VALUE", "Loại luật không hợp lệ.");
        }
        if (BadgeRule.PROGRAM_COMPLETED.equals(type)) {
            if (request.paramProgramId() == null || !repository.programExists(request.paramProgramId())) {
                throw BusinessException.validation("paramProgramId", "INVALID_VALUE",
                    "Chọn một chương trình đào tạo.");
            }
            return new Rule(name, description, icon, type, null, request.paramProgramId());
        }
        Integer n = request.paramN();
        if (n == null || n < 1 || n > MAX_N) {
            throw BusinessException.validation("paramN", "INVALID_VALUE", "Ngưỡng phải từ 1 đến " + MAX_N + ".");
        }
        return new Rule(name, description, icon, type, n, null);
    }

    private static Map<String, Object> changes(Rule rule) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("name", rule.name());
        changes.put("ruleType", rule.type());
        changes.put("paramN", rule.n());
        changes.put("paramProgramId", rule.programId());
        return changes;
    }

    private AdminBadgeView view(AdminRow row) {
        Definition d = row.definition();
        String program = d.paramProgramId() == null ? null
            : repository.programNames(List.of(d.paramProgramId())).get(d.paramProgramId());
        return new AdminBadgeView(d.badgeId(), d.code(), d.name(), d.description(), d.icon(), d.ruleType(),
            d.paramN(), d.paramProgramId(), program, BadgeService.condition(d, program), d.status(),
            row.holders() > 0, row.holders(), row.backfillStatus(), row.backfillTotal(), row.backfillDone());
    }

    private Actor requireSystemAdmin() {
        Actor actor = access.actor();
        if (!XpAdjustmentService.isSystemAdmin(actor)) {
            throw new AccessDeniedException("System admin required");
        }
        return actor;
    }
}
