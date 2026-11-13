package vn.com.coreacademy.scorm.service;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;
import vn.com.coreacademy.learning.service.LearningScormDirectory;
import vn.com.coreacademy.scorm.repository.ScormRepository;

@Component
@RequiredArgsConstructor
public class ScormCourseDirectory implements LearningScormDirectory {
    private final ScormRepository repository;
    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final IdentityAuditService audit;

    @Override
    public void validate(Long packageId, Long ownerOrgId, boolean required) {
        if (packageId == null) {
            if (required) {
                throw BusinessException.validation("scormPackageId", "REQUIRED",
                    "Vui lòng tải lên hoặc chọn bài giảng SCORM.");
            }
            return;
        }
        var value = repository.require(packageId);
        var actor = access.actor();
        boolean managed = access.canManage(actor, value.orgId(),
            value.uploadedBy().equals(actor.person().id()) ? actor.person().teamId() : null, value.uploadedBy());
        boolean shared = value.published() && identity.activeOrgIds(value.orgId(), true).contains(ownerOrgId);
        if (!managed && !shared) {
            throw new AccessDeniedException("SCORM library scope denied");
        }
    }

    /** Gói mới được xuất bản trong cùng transaction với phiên bản khóa học. */
    @Override
    public void publish(Long packageId, Long revisionId, String actor) {
        var value = repository.require(packageId);
        if (value.published()) {
            return;
        }
        if (!repository.publish(value.id(), value.version())) {
            throw BusinessException.validation("scormPackageId", "INVALID_VALUE",
                "Bài giảng đã thay đổi. Tải lại khóa học.");
        }
        audit.record(actor, "SCORM_PUBLISHED", "scorm_package", Long.toString(value.id()), value.orgId(),
            Map.of("revisionId", revisionId));
    }
}
