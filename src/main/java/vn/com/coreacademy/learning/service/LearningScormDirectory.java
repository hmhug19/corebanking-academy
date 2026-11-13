package vn.com.coreacademy.learning.service;

/** Package validation boundary: Learning does not depend on the SCORM runtime. */
public interface LearningScormDirectory {
    void validate(Long packageId, Long ownerOrgId, boolean published);
}
