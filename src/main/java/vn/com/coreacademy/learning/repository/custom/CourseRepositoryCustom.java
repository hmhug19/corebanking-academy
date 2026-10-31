package vn.com.coreacademy.learning.repository.custom;

import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseCard;
import vn.com.coreacademy.learning.dto.LearningDetails.LearnerFacets;
import vn.com.coreacademy.learning.dto.LearningProgressOverview.Counts;
import vn.com.coreacademy.learning.service.LearningAccessPolicy.SearchScope;

public interface CourseRepositoryCustom {
    /** failedCourseIds: khóa học viên thi cuối khóa chưa đạt, do service lấy qua cổng quiz; rỗng ở chế độ MANAGE. */
    record CourseSearch(String mode, String actorId, Long actorOrgId, Set<Long> ancestorIds, SearchScope scope,
                        String q, Long fieldId, Long programId, Long moduleId, String level,
                        String requirement, String learningStatus, String publicationState, Boolean overdue,
                        Boolean assigned, Boolean failed, Set<Long> failedCourseIds) {
    }

    Counts summary(String userId);

    Page<CourseCard> search(CourseSearch filter, Pageable pageable);

    LearnerFacets facets(CourseSearch filter);
}
