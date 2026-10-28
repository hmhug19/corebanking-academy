package vn.com.coreacademy.assessment.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AuditAction;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ItemStatus;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.entity.Competency;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.mapper.AssessmentMapper;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LearningWriteLock;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CompetencyService {
    private final LearningAssessmentAccess learning;
    private final AssessmentRepository repository;
    private final AssessmentMapper mapper;
    private final LearningWriteLock lock;
    private final IdentityAuditService audit;

    /** Danh mục dùng chung; mọi tài khoản đã đăng nhập đều đọc được để hiển thị nhãn năng lực. */
    public List<AssessmentViews.CompetencyView> list(String status) {
        learning.actor();
        if (status != null && !Set.of(ItemStatus.ACTIVE, ItemStatus.INACTIVE).contains(status)) {
            throw BusinessException.validation("status", "INVALID_VALUE", "Giá trị không hợp lệ.");
        }
        return repository.competencies().stream()
            .filter(c -> status == null || status.equals(c.getStatus()))
            .map(mapper::view).toList();
    }

    /**
     * Tạo hoặc cập nhật nhóm năng lực. Chỉ quản trị phạm vi toàn hệ thống được ghi vì danh mục dùng chung cho mọi
     * đơn vị; nhóm cha phải tồn tại và không tạo chu trình.
     */
    @Transactional
    public AssessmentViews.CompetencyView save(Long id, Long expectedVersion,
                                               AssessmentRequests.CompetencyRequest request) {
        Actor actor = learning.actor();
        requireGlobalAdmin(actor);
        lock.acquire();

        Competency competency = id == null ? new Competency() : repository.require(Competency.class, id);
        if (Boolean.TRUE.equals(competency.getDeleted())) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
        if (id != null && !Objects.equals(competency.getRowVersion(), expectedVersion)) {
            throw new BusinessException(AssessmentResponseCode.CONFLICT);
        }
        String code = request.code().trim();
        if (repository.competencyCodeExists(code, id)) {
            throw BusinessException.field(AssessmentResponseCode.DUPLICATE, "code", "ALREADY_EXISTS",
                "Mã nhóm năng lực đã tồn tại.");
        }
        validateParent(id, request.parentId());

        competency.setCode(code);
        competency.setName(request.name().trim());
        competency.setDescription(request.description());
        competency.setParentId(request.parentId());
        competency.setSortNo(request.sortNo());
        competency.setStatus(request.status());
        repository.save(competency, actor.person().id());
        repository.flush();

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("code", code);
        changes.put("status", request.status());
        audit.record(actor.person().id(), AuditAction.COMPETENCY_SAVED, "competency", competency.getId().toString(),
            null, changes);
        return mapper.view(competency);
    }

    /**
     * Xóa mềm nhóm năng lực không còn câu hỏi chưa xóa hoặc nhóm con gắn vào. Snapshot lượt thi cũ vẫn giữ
     * competency_id nên báo cáo lịch sử vẫn đọc được tên nhóm.
     */
    @Transactional
    public void delete(Long id, Long expectedVersion) {
        Actor actor = learning.actor();
        requireGlobalAdmin(actor);
        lock.acquire();

        Competency competency = repository.require(Competency.class, id);
        if (Boolean.TRUE.equals(competency.getDeleted())) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
        if (!Objects.equals(competency.getRowVersion(), expectedVersion)) {
            throw new BusinessException(AssessmentResponseCode.CONFLICT);
        }
        if (repository.competencyInUse(id)) {
            throw new BusinessException(AssessmentResponseCode.COMPETENCY_IN_USE);
        }
        competency.setDeleted(true);
        repository.save(competency, actor.person().id());
        repository.flush();

        audit.record(actor.person().id(), AuditAction.COMPETENCY_DELETED, "competency", id.toString(), null,
            Map.of("code", competency.getCode()));
    }

    private void validateParent(Long id, Long parentId) {
        Set<Long> seen = new HashSet<>();
        Long current = parentId;
        while (current != null) {
            if (current.equals(id) || !seen.add(current)) {
                throw BusinessException.field(AssessmentResponseCode.INVALID_REFERENCE, "parentId", "INVALID_VALUE",
                    "Nhóm cha không hợp lệ hoặc tạo vòng lặp.");
            }
            Competency parent = repository.competencies(List.of(current)).stream()
                .filter(c -> !Boolean.TRUE.equals(c.getDeleted())).findFirst()
                .orElseThrow(() -> BusinessException.field(AssessmentResponseCode.INVALID_REFERENCE, "parentId",
                    "NOT_FOUND", "Nhóm cha không tồn tại."));
            current = parent.getParentId();
        }
    }

    private static void requireGlobalAdmin(Actor actor) {
        boolean allowed = actor.grants().stream().anyMatch(g -> g.scope().type() == ScopeType.GLOBAL
            && Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN).contains(g.roleCode()));
        if (!allowed) {
            throw new AccessDeniedException("Competency management requires global admin");
        }
    }
}
