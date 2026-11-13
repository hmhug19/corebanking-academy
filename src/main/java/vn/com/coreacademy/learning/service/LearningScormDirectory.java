package vn.com.coreacademy.learning.service;

/** Package validation boundary: Learning does not depend on the SCORM runtime. */
public interface LearningScormDirectory {
    /** Gói có thể còn nháp; bắt buộc có liên kết khi chuẩn bị xuất bản khóa học. */
    void validate(Long packageId, Long ownerOrgId, boolean required);

    void publish(Long packageId, Long revisionId, String actor);
}
