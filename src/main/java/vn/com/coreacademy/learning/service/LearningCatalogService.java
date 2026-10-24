package vn.com.coreacademy.learning.service;

import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.learning.constants.LearningCodes.AuditAction;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.entity.BusinessModule;
import vn.com.coreacademy.learning.entity.TrainingField;
import vn.com.coreacademy.learning.entity.TrainingProgram;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.mapper.LearningMapper;
import vn.com.coreacademy.learning.repository.BusinessModuleRepository;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.learning.repository.TrainingFieldRepository;
import vn.com.coreacademy.learning.repository.TrainingProgramRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningCatalogService {
    private final LearningRepository repository;
    private final TrainingFieldRepository fields;
    private final TrainingProgramRepository programs;
    private final BusinessModuleRepository modules;
    private final LearningAccessPolicy access;
    private final LearningWriteLock lock;
    private final LearningMapper mapper;
    private final IdentityAuditService audit;

    public PageResponse<LearningViews.TrainingFieldView> fields(Long orgId, int page, int size) {
        var actor = access.actor();
        access.requireManage(actor, orgId, actor.person().teamId(), actor.person().id());
        return PageResponse.from(fields.findByOwnerOrgId(orgId,
            PageQuery.of(page, size, "id,asc", Set.of("id"))).map(mapper::view));
    }

    public PageResponse<LearningViews.TrainingProgramView> programs(Long orgId, int page, int size) {
        var actor = access.actor();
        access.requireManage(actor, orgId, actor.person().teamId(), actor.person().id());
        return PageResponse.from(programs.findByOwnerOrgId(orgId,
            PageQuery.of(page, size, "id,asc", Set.of("id"))).map(mapper::view));
    }

    public PageResponse<LearningViews.BusinessModuleView> modules(Long fieldId, int page, int size) {
        TrainingField field = repository.require(TrainingField.class, fieldId);
        var actor = access.actor();
        access.requireManage(actor, field.getOwnerOrgId(), actor.person().teamId(), actor.person().id());
        return PageResponse.from(modules.findByFieldId(fieldId,
            PageQuery.of(page, size, "id,asc", Set.of("id"))).map(mapper::view));
    }

    @Transactional
    public LearningViews.TrainingFieldView saveField(Long id, Long version, LearningRequests.CatalogRequest request) {
        // Bước 1: chỉ quản lý danh mục trong scope thật; khóa kiểm tra mã trước INSERT/UPDATE.
        var actor = access.actor();
        access.requireManage(actor, request.ownerOrgId(), actor.person().teamId(), actor.person().id());
        lock.acquire();
        TrainingField value = id == null ? new TrainingField() : repository.require(TrainingField.class, id);
        if (id != null) {
            LearningContentService.version(value, version);
            requireSameOrg(value.getOwnerOrgId(), request.ownerOrgId());
        }
        if (request.fieldId() != null || fields.existsByCodeIgnoreCaseAndIdNot(request.code().strip(),
            id == null ? -1L : id)) {
            throw new BusinessException(LearningResponseCode.DUPLICATE);
        }

        // Bước 2: chỉ map field được phép sửa; actor/version không lấy từ payload.
        value.setOwnerOrgId(request.ownerOrgId());
        value.setCode(request.code().strip());
        value.setName(request.name().strip());
        value.setDescription(request.description());
        value.setStatus(request.status());
        value.setSortNo(request.sortNo());
        repository.save(value, actor.person().id());
        audit.record(actor.person().id(), AuditAction.FIELD_SAVED, "training_field", value.getId().toString(),
            value.getOwnerOrgId(), Map.of());
        repository.flush();
        return mapper.view(value);
    }

    @Transactional
    public LearningViews.TrainingProgramView saveProgram(Long id, Long version,
                                                         LearningRequests.CatalogRequest request) {
        var actor = access.actor();
        access.requireManage(actor, request.ownerOrgId(), actor.person().teamId(), actor.person().id());
        lock.acquire();
        TrainingField parent = repository.require(TrainingField.class, request.fieldId());
        requireSameOrg(parent.getOwnerOrgId(), request.ownerOrgId());
        TrainingProgram value = id == null ? new TrainingProgram() : repository.require(TrainingProgram.class, id);
        if (id != null) {
            LearningContentService.version(value, version);
            requireSameOrg(value.getOwnerOrgId(), request.ownerOrgId());
            // Không chuyển lĩnh vực chương trình đã tạo: tránh phá quan hệ module của các revision cũ.
            if (!value.getFieldId().equals(parent.getId())) {
                throw new BusinessException(LearningResponseCode.CONFLICT);
            }
        }
        if (programs.existsByCodeIgnoreCaseAndIdNot(request.code().strip(), id == null ? -1L : id)) {
            throw new BusinessException(LearningResponseCode.DUPLICATE);
        }
        value.setFieldId(parent.getId());
        value.setOwnerOrgId(request.ownerOrgId());
        value.setCode(request.code().strip());
        value.setName(request.name().strip());
        value.setDescription(request.description());
        value.setStatus(request.status());
        value.setSortNo(request.sortNo());
        repository.save(value, actor.person().id());
        audit.record(actor.person().id(), AuditAction.PROGRAM_SAVED, "training_program", value.getId().toString(),
            value.getOwnerOrgId(), Map.of());
        repository.flush();
        return mapper.view(value);
    }

    @Transactional
    public LearningViews.BusinessModuleView saveModule(Long id, Long version, LearningRequests.CatalogRequest request) {
        var actor = access.actor();
        access.requireManage(actor, request.ownerOrgId(), actor.person().teamId(), actor.person().id());
        lock.acquire();
        TrainingField field = repository.require(TrainingField.class, request.fieldId());
        requireSameOrg(field.getOwnerOrgId(), request.ownerOrgId());
        BusinessModule value = id == null ? new BusinessModule() : repository.require(BusinessModule.class, id);
        if (id != null) {
            LearningContentService.version(value, version);
            if (!value.getFieldId().equals(field.getId())) {
                throw new BusinessException(LearningResponseCode.CONFLICT);
            }
        }
        if (modules.existsByFieldIdAndCodeIgnoreCaseAndIdNot(field.getId(), request.code().strip(),
            id == null ? -1L : id)) {
            throw new BusinessException(LearningResponseCode.DUPLICATE);
        }
        value.setFieldId(field.getId());
        value.setCode(request.code().strip());
        value.setName(request.name().strip());
        value.setStatus(request.status());
        value.setSortNo(request.sortNo());
        repository.save(value, actor.person().id());
        audit.record(actor.person().id(), AuditAction.MODULE_SAVED, "business_module", value.getId().toString(),
            field.getOwnerOrgId(), Map.of());
        repository.flush();
        return mapper.view(value);
    }

    private void requireSameOrg(Long source, Long target) {
        if (!source.equals(target)) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
    }
}
