package vn.com.coreacademy.learning.service;

import java.util.Map;

/** Điểm mở rộng trong transaction Learning cho tài liệu tham khảo thuộc module khác. */
public interface LearningResourceExtension {
    void validateBeforePublish(Long courseRevisionId);

    void cloneResources(Long sourceRevisionId, Long targetRevisionId, Map<Long, Long> lessonIds);
}
