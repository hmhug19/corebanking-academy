package vn.com.coreacademy.scorm.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;
import vn.com.coreacademy.learning.service.LearningScormDirectory;
import vn.com.coreacademy.scorm.repository.ScormRepository;

@Component
@RequiredArgsConstructor
public class ScormCourseDirectory implements LearningScormDirectory {
    private final ScormRepository repository;
    private final LearningAccessPolicy access;

    @Override
    public void validate(Long packageId, Long ownerOrgId, boolean published) {
        if (packageId == null) {
            throw BusinessException.validation("scormPackageId", "REQUIRED", "Chọn gói SCORM cho bài học.");
        }
        var value = repository.require(packageId);
        var actor = access.actor();
        if (!ownerOrgId.equals(value.orgId()) || !access.canManage(actor, value.orgId(),
            value.uploadedBy().equals(actor.person().id()) ? actor.person().teamId() : null, value.uploadedBy())) {
            throw BusinessException.validation("scormPackageId", "INVALID_VALUE", "Gói SCORM không thuộc phạm vi quản lý.");
        }
        if (published && !value.published()) {
            throw BusinessException.validation("scormPackageId", "INVALID_VALUE", "Xuất bản gói SCORM trước khi xuất bản khóa học.");
        }
    }
}
