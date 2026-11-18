package vn.com.coreacademy.learning.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.learning.constants.LearningCodes.AuditAction;
import vn.com.coreacademy.learning.constants.LearningCodes.CatalogStatus;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.entity.BusinessModule;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.entity.TrainingField;
import vn.com.coreacademy.learning.entity.TrainingProgram;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.mapper.LearningMapper;
import vn.com.coreacademy.learning.repository.BusinessModuleRepository;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.learning.repository.TrainingFieldRepository;
import vn.com.coreacademy.learning.repository.TrainingProgramRepository;
import vn.com.coreacademy.learning.service.LearningAccessPolicy.CatalogPermission;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningCatalogService {
    private static final String COPY_SUFFIX = " (bản sao)";

    private final LearningRepository repository;
    private final TrainingFieldRepository fields;
    private final TrainingProgramRepository programs;
    private final BusinessModuleRepository modules;
    private final LearningAccessPolicy access;
    private final LearningWriteLock lock;
    private final LearningMapper mapper;
    private final LearningContentService content;
    private final IdentityAuditService audit;

    private record CatalogScope(List<TrainingField> fields, List<TrainingProgram> programs) {
    }

    private CatalogScope catalogScope(Long orgId, CatalogPermission permission) {
        if (permission.orgWide()) {
            return new CatalogScope(repository.fields(orgId), repository.programs(orgId));
        }
        Set<Long> scopedPrograms = access.scopedProgramIds(orgId, permission);
        List<TrainingProgram> visiblePrograms = repository.programs(orgId).stream()
            .filter(program -> scopedPrograms.contains(program.getId()) || permission.includesOwner(program.getCreatedBy()))
            .toList();
        Set<Long> parentIds = new HashSet<>();
        visiblePrograms.forEach(program -> parentIds.add(program.getFieldId()));
        List<TrainingField> visibleFields = repository.fields(orgId).stream()
            .filter(field -> parentIds.contains(field.getId()) || permission.includesOwner(field.getCreatedBy()))
            .toList();
        return new CatalogScope(visibleFields, visiblePrograms);
    }

    private <T> PageResponse<T> page(List<T> values, int index, int size) {
        var paging = PageQuery.of(index, size, "id,asc", Set.of("id"));
        int start = (int) Math.min(values.size(), paging.getOffset());
        int end = Math.min(values.size(), start + paging.getPageSize());
        return PageResponse.from(new PageImpl<>(values.subList(start, end), paging, values.size()));
    }

    public PageResponse<LearningViews.TrainingFieldView> fields(Long orgId, int page, int size) {
        CatalogPermission permission = access.catalogPermission(access.actor(), orgId);
        if (permission.orgWide()) {
            return PageResponse.from(fields.findByOwnerOrgId(orgId,
                PageQuery.of(page, size, "id,asc", Set.of("id"))).map(mapper::view));
        }
        return page(catalogScope(orgId, permission).fields().stream()
            .sorted(Comparator.comparing(TrainingField::getId)).map(mapper::view).toList(), page, size);
    }

    public PageResponse<LearningViews.TrainingProgramView> programs(Long orgId, int page, int size) {
        CatalogPermission permission = access.catalogPermission(access.actor(), orgId);
        if (permission.orgWide()) {
            return PageResponse.from(programs.findByOwnerOrgId(orgId,
                PageQuery.of(page, size, "id,asc", Set.of("id"))).map(mapper::view));
        }
        return page(catalogScope(orgId, permission).programs().stream()
            .sorted(Comparator.comparing(TrainingProgram::getId)).map(mapper::view).toList(), page, size);
    }

    /** Cây lĩnh vực → chương trình → khóa học của một đơn vị, cùng quyền quản lý với các danh sách phẳng. */
    public LearningViews.CatalogTreeView tree(Long orgId) {
        CatalogPermission permission = access.catalogPermission(access.actor(), orgId);
        CatalogScope scope = catalogScope(orgId, permission);
        Map<Long, List<LearningViews.CatalogTreeCourse>> coursesByProgram = new HashMap<>();
        for (Object[] row : repository.treeCourses(orgId)) {
            Course course = (Course) row[0];
            if (!permission.includesCourse(course)) {
                continue;
            }
            CourseRevision revision = (CourseRevision) row[1];
            coursesByProgram.computeIfAbsent(revision.getProgramId(), key -> new ArrayList<>())
                .add(new LearningViews.CatalogTreeCourse(course.getId(), course.getCode(), revision.getTitle(),
                    course.getLifecycleStatus(), course.getPublishedRevisionId() != null));
        }
        Map<Long, List<LearningViews.CatalogTreeProgram>> programsByField = new HashMap<>();
        for (TrainingProgram program : scope.programs()) {
            programsByField.computeIfAbsent(program.getFieldId(), key -> new ArrayList<>())
                .add(new LearningViews.CatalogTreeProgram(program.getId(), program.getFieldId(),
                    program.getOwnerOrgId(), program.getCode(), program.getName(), program.getDescription(),
                    program.getStatus(), program.getSortNo(), program.getRowVersion(),
                    coursesByProgram.getOrDefault(program.getId(), List.of())));
        }
        return new LearningViews.CatalogTreeView(scope.fields().stream()
            .map(field -> new LearningViews.CatalogTreeField(field.getId(), field.getOwnerOrgId(), field.getCode(),
                field.getName(), field.getDescription(), field.getStatus(), field.getSortNo(),
                field.getRowVersion(), programsByField.getOrDefault(field.getId(), List.of())))
            .toList());
    }

    public PageResponse<LearningViews.BusinessModuleView> modules(Long fieldId, int page, int size) {
        TrainingField field = repository.require(TrainingField.class, fieldId);
        Long orgId = field.getOwnerOrgId();
        CatalogPermission permission = access.catalogPermission(access.actor(), orgId);
        if (!permission.includesOwner(field.getCreatedBy()) && catalogScope(orgId, permission).fields().stream()
            .noneMatch(visible -> fieldId.equals(visible.getId()))) {
            throw new AccessDeniedException("Training field scope denied");
        }
        return PageResponse.from(modules.findByFieldId(fieldId,
            PageQuery.of(page, size, "id,asc", Set.of("id"))).map(mapper::view));
    }

    @Transactional
    public LearningViews.TrainingFieldView saveFieldWithModules(Long id, Long version,
                                                               LearningRequests.FieldSaveRequest request) {
        var saved = saveField(id, version, request.catalog());
        if (request.modules() == null) {
            return saved;
        }

        Set<Long> seen = new HashSet<>();
        for (var change : request.modules()) {
            if (change.id() != null) {
                if (!seen.add(change.id()) || change.expectedVersion() == null || id == null) {
                    throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
                }
                BusinessModule module = repository.require(BusinessModule.class, change.id());
                if (!module.getFieldId().equals(saved.id())) {
                    throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
                }
            }
            if (change.deleted()) {
                if (change.id() == null) {
                    throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
                }
                deleteModule(change.id(), change.expectedVersion());
            } else {
                saveModule(change.id(), change.expectedVersion(), new LearningRequests.CatalogRequest(
                    request.ownerOrgId(), saved.id(), change.code(), change.name(), null,
                    change.status(), change.sortNo()));
            }
        }
        return saved;
    }

    @Transactional
    public LearningViews.TrainingFieldView saveField(Long id, Long version, LearningRequests.CatalogRequest request) {
        // Bước 1: chỉ quản lý danh mục trong scope thật; khóa kiểm tra mã trước INSERT/UPDATE.
        var actor = access.actor();
        lock.acquire();
        TrainingField value = id == null ? new TrainingField() : repository.require(TrainingField.class, id);
        access.requireManage(actor, request.ownerOrgId(), null, id == null ? actor.person().id() : value.getCreatedBy());
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
        lock.acquire();
        TrainingField parent = repository.require(TrainingField.class, request.fieldId());
        requireSameOrg(parent.getOwnerOrgId(), request.ownerOrgId());
        access.requireManage(actor, parent.getOwnerOrgId(), null, parent.getCreatedBy());
        TrainingProgram value = id == null ? new TrainingProgram() : repository.require(TrainingProgram.class, id);
        access.requireManage(actor, request.ownerOrgId(), null, id == null ? actor.person().id() : value.getCreatedBy());
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

    /** Xóa lĩnh vực trống; còn chương trình hoặc phân hệ thì từ chối để người dùng chuyển sang Lưu trữ. */
    @Transactional
    public void deleteField(Long id, Long version) {
        var actor = access.actor();
        TrainingField value = repository.require(TrainingField.class, id);
        access.requireManage(actor, value.getOwnerOrgId(), null, value.getCreatedBy());
        lock.acquire();
        repository.refresh(value);
        LearningContentService.version(value, version);
        if (repository.fieldInUse(id)) {
            throw new BusinessException(LearningResponseCode.CATALOG_IN_USE);
        }
        repository.remove(value);
        audit.record(actor.person().id(), AuditAction.FIELD_DELETED, "training_field", id.toString(),
            value.getOwnerOrgId(), Map.of());
        repository.flush();
    }

    /** Xóa chương trình trống; còn khóa học hoặc câu hỏi thì từ chối để người dùng chuyển sang Lưu trữ. */
    @Transactional
    public void deleteProgram(Long id, Long version) {
        var actor = access.actor();
        TrainingProgram value = repository.require(TrainingProgram.class, id);
        access.requireManage(actor, value.getOwnerOrgId(), null, value.getCreatedBy());
        lock.acquire();
        repository.refresh(value);
        LearningContentService.version(value, version);
        if (repository.programInUse(id)) {
            throw new BusinessException(LearningResponseCode.CATALOG_IN_USE);
        }
        repository.remove(value);
        audit.record(actor.person().id(), AuditAction.PROGRAM_DELETED, "training_program", id.toString(),
            value.getOwnerOrgId(), Map.of());
        repository.flush();
    }

    /** Sao chép chương trình thành bản nháp cùng lĩnh vực, kèm bản nháp của mọi khóa học của nó. */
    @Transactional
    public LearningViews.TrainingProgramView copyProgram(Long id) {
        var actor = access.actor();
        TrainingProgram source = repository.require(TrainingProgram.class, id);
        access.requireManage(actor, source.getOwnerOrgId(), null, source.getCreatedBy());
        lock.acquire();
        repository.refresh(source);

        TrainingProgram copy = new TrainingProgram();
        copy.setFieldId(source.getFieldId());
        copy.setOwnerOrgId(source.getOwnerOrgId());
        copy.setCode(content.freeCopyCode(source.getCode(), code -> programs.existsByCodeIgnoreCaseAndIdNot(code, -1L)));
        copy.setName(source.getName().substring(0, Math.min(source.getName().length(), 255 - COPY_SUFFIX.length()))
            + COPY_SUFFIX);
        copy.setDescription(source.getDescription());
        copy.setStatus(CatalogStatus.DRAFT);
        copy.setSortNo(source.getSortNo());
        copy.setCopiedFromProgramId(source.getId());
        repository.save(copy, actor.person().id());
        repository.flush();

        CatalogPermission permission = access.catalogPermission(actor, source.getOwnerOrgId());
        for (Object[] row : repository.treeCourses(source.getOwnerOrgId())) {
            CourseRevision revision = (CourseRevision) row[1];
            if (source.getId().equals(revision.getProgramId())) {
                Course course = (Course) row[0];
                if (!permission.includesCourse(course)) {
                    continue;
                }
                content.copyIntoProgram(course, copy.getId(),
                    content.freeCopyCode(course.getCode(), repository::courseCodeExists));
                repository.flush();
            }
        }
        audit.record(actor.person().id(), AuditAction.PROGRAM_COPIED, "training_program", copy.getId().toString(),
            copy.getOwnerOrgId(), Map.of("sourceId", source.getId()));
        return mapper.view(copy);
    }

    @Transactional
    public LearningViews.BusinessModuleView saveModule(Long id, Long version, LearningRequests.CatalogRequest request) {
        var actor = access.actor();
        lock.acquire();
        TrainingField field = repository.require(TrainingField.class, request.fieldId());
        access.requireManage(actor, field.getOwnerOrgId(), null, field.getCreatedBy());
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

    @Transactional
    public void deleteModule(Long id, Long version) {
        var actor = access.actor();
        lock.acquire();
        BusinessModule value = repository.require(BusinessModule.class, id);
        TrainingField field = repository.require(TrainingField.class, value.getFieldId());
        access.requireManage(actor, field.getOwnerOrgId(), null, field.getCreatedBy());
        LearningContentService.version(value, version);
        if (repository.moduleInUse(id)) {
            throw new BusinessException(LearningResponseCode.CATALOG_IN_USE);
        }
        repository.remove(value);
        audit.record(actor.person().id(), AuditAction.MODULE_DELETED, "business_module", id.toString(),
            field.getOwnerOrgId(), Map.of());
        repository.flush();
    }
}
