package vn.com.coreacademy.learning.service;

import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.LearningRepository;

/** Chỉ xuất DTO bất biến; Community không đọc entity/repository hoặc tự suy ra quyền Learning. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommunityLearningAdapter {
    private final LearningRepository repository;
    private final LearningAccessPolicy access;
    // Enrollment policy phụ thuộc ContentService; provider chỉ resolve lúc gọi để không tạo constructor cycle qua port.
    private final ObjectProvider<LearningAssignmentService> assignments;

    public record RevisionContext(Long courseId, Long revisionId, Long version, Long ownerOrgId, String title,
                                   List<Long> lessonIds, String state, boolean canManage) {
    }

    public RevisionContext readable(Long revisionId) {
        var actor = access.actor();
        var revision = repository.require(CourseRevision.class, revisionId);
        var course = repository.require(Course.class, revision.getCourseId());
        boolean manager = access.canManage(actor, course.getOwnerOrgId(), course.getOwnerTeamId(), course.getOwnerUserId());
        if (manager) {
            access.requireManage(actor, course);
        } else {
            var history = repository.enrollmentHistory(course.getId(), actor.person().id()).stream()
                .filter(enrollment -> Objects.equals(enrollment.getRevisionId(), revisionId)
                    && !"withdrawn".equals(enrollment.getStatus())).findFirst()
                .orElseThrow(() -> new AccessDeniedException("Learning enrollment required"));
            assignments.getObject().requireOwnEnrollment(actor, history.getId(), true);
        }
        return context(course, revision, manager);
    }

    public RevisionContext editable(Long revisionId) {
        var actor = access.actor();
        var revision = repository.require(CourseRevision.class, revisionId);
        var course = repository.require(Course.class, revision.getCourseId());
        access.requireManage(actor, course);
        if (!"draft".equals(revision.getState()) || !"active".equals(course.getLifecycleStatus())) {
            throw new BusinessException(LearningResponseCode.CONFLICT);
        }
        return context(course, revision, true);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long advance(Long revisionId, Long expectedVersion) {
        editable(revisionId);
        var revision = repository.require(CourseRevision.class, revisionId);
        LearningContentService.version(revision, expectedVersion);
        repository.incrementVersion(revision);
        repository.flush();
        return revision.getRowVersion();
    }

    private RevisionContext context(Course course, CourseRevision revision, boolean manager) {
        return new RevisionContext(course.getId(), revision.getId(), revision.getRowVersion(), course.getOwnerOrgId(),
            revision.getTitle(), repository.lessons(revision.getId()).stream().map(CourseLesson::getId).toList(),
            revision.getState(), manager);
    }
}
