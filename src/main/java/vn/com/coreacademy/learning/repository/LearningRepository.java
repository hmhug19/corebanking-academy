package vn.com.coreacademy.learning.repository;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.constants.LearningCodes.AssignmentStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.RecipientStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.ReminderStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.ShareStatus;
import vn.com.coreacademy.learning.entity.AssignmentRecipient;
import vn.com.coreacademy.learning.entity.AssignmentTarget;
import vn.com.coreacademy.learning.entity.BlockAsset;
import vn.com.coreacademy.learning.entity.BlockProgress;
import vn.com.coreacademy.learning.entity.BusinessModule;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseAssessment;
import vn.com.coreacademy.learning.entity.CourseAssignment;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseCompletionRule;
import vn.com.coreacademy.learning.entity.CourseEnrollment;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.CourseObjective;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.entity.CourseShare;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.entity.LearningGroup;
import vn.com.coreacademy.learning.entity.LearningGroupMember;
import vn.com.coreacademy.learning.entity.LearningRecord;
import vn.com.coreacademy.learning.entity.LearningReminder;
import vn.com.coreacademy.learning.entity.LessonBlock;
import vn.com.coreacademy.learning.entity.LessonProgress;
import vn.com.coreacademy.learning.entity.ProcessStep;
import vn.com.coreacademy.learning.entity.RecipientTargetSource;
import vn.com.coreacademy.learning.entity.TrainingField;
import vn.com.coreacademy.learning.entity.TrainingProgram;
import vn.com.coreacademy.learning.entity.VideoWatchRange;
import vn.com.coreacademy.learning.exception.LearningResponseCode;

/** Truy cập scalar entity, không có quan hệ JPA hay FK; query nghiệp vụ chỉ đặt tại repository. */
@Repository
@RequiredArgsConstructor
public class LearningRepository {
    private final EntityManager entityManager;

    public <T> T require(Class<T> type, Long id) {
        T value = id == null ? null : entityManager.find(type, id);
        if (value == null) {
            throw new BusinessException(LearningResponseCode.NOT_FOUND);
        }
        return value;
    }

    public <T extends LearningRecord> T save(T value, String actor) {
        if (value.getCreatedBy() == null) {
            value.setCreatedBy(actor);
        }
        value.setUpdatedBy(actor);
        if (!entityManager.contains(value)) {
            entityManager.persist(value);
        }
        return value;
    }

    public void remove(LearningRecord value) {
        entityManager.remove(value);
    }

    public void flush() {
        entityManager.flush();
    }

    public void refresh(LearningRecord value) {
        entityManager.refresh(value);
    }

    public void incrementVersion(LearningRecord value) {
        entityManager.lock(value, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    public boolean teamInUse(Long teamId) {
        return Boolean.TRUE.equals(entityManager.createNativeQuery("""
            select exists (select 1 from learning_hub.courses where owner_team_id = :id)
                or exists (select 1 from learning_hub.assignment_targets where team_id = :id)
                or exists (select 1 from learning_hub.assignment_recipients where team_id_snapshot = :id)
                or exists (select 1 from learning_hub.course_enrollments where team_id_snapshot = :id)
            """).setParameter("id", teamId).getSingleResult());
    }

    private <T> List<T> query(String jpql, Class<T> type, Map<String, Object> parameters) {
        var query = entityManager.createQuery(jpql, type);
        parameters.forEach(query::setParameter);
        return query.getResultList();
    }

    public List<TrainingField> fields(Long orgId) {
        return query("""
            select f
            from TrainingField f
            where f.ownerOrgId = :org
            order by f.sortNo, f.id
            """, TrainingField.class, Map.of("org", orgId));
    }

    public List<TrainingProgram> programs(Long orgId) {
        return query("""
            select p
            from TrainingProgram p
            where p.ownerOrgId = :org
            order by p.sortNo, p.id
            """, TrainingProgram.class, Map.of("org", orgId));
    }

    public List<BusinessModule> modules(Long fieldId) {
        return query("""
            select m
            from BusinessModule m
            where m.fieldId = :id
            order by m.sortNo, m.id
            """, BusinessModule.class, Map.of("id", fieldId));
    }

    public List<CourseRevision> revisions(Long courseId) {
        return query("""
            select r
            from CourseRevision r
            where r.courseId = :id
            order by r.revisionNo desc
            """, CourseRevision.class, Map.of("id", courseId));
    }

    public List<CourseObjective> objectives(Long revisionId) {
        return query("""
            select o
            from CourseObjective o
            where o.revisionId = :id
            order by o.sortNo, o.id
            """, CourseObjective.class, Map.of("id", revisionId));
    }

    public List<CourseChapter> chapters(Long revisionId) {
        return query("""
            select c
            from CourseChapter c
            where c.revisionId = :id
            order by c.sortNo, c.id
            """, CourseChapter.class, Map.of("id", revisionId));
    }

    public List<CourseLesson> lessons(Long revisionId) {
        return query("""
            select l
            from CourseLesson l, CourseChapter c
            where l.chapterId = c.id
            and c.revisionId = :id
            order by c.sortNo, l.sortNo, l.id
            """, CourseLesson.class, Map.of("id", revisionId));
    }

    public List<LessonBlock> blocks(Long revisionId) {
        return query("""
            select b
            from LessonBlock b, CourseLesson l, CourseChapter c
            where b.lessonId = l.id
            and l.chapterId = c.id
            and c.revisionId = :id
            order by b.sortNo, b.id
            """, LessonBlock.class, Map.of("id", revisionId));
    }

    public List<ProcessStep> steps(Long revisionId) {
        return query("""
            select s
            from ProcessStep s, LessonBlock b, CourseLesson l, CourseChapter c
            where s.blockId = b.id
            and b.lessonId = l.id
            and l.chapterId = c.id
            and c.revisionId = :id
            order by s.sortNo, s.id
            """, ProcessStep.class, Map.of("id", revisionId));
    }

    public List<BlockAsset> assetLinks(Long revisionId) {
        return query("""
            select a
            from BlockAsset a, LessonBlock b, CourseLesson l, CourseChapter c
            where a.blockId = b.id
            and b.lessonId = l.id
            and l.chapterId = c.id
            and c.revisionId = :id
            order by a.sortNo, a.id
            """, BlockAsset.class, Map.of("id", revisionId));
    }

    public List<LearningAsset> assets(List<Long> ids) {
        return query("""
            select a
            from LearningAsset a
            where a.id in :ids
            """, LearningAsset.class, Map.of("ids", ids.isEmpty() ? List.of(-1L) : ids));
    }

    public List<CourseCompletionRule> rules(Long revisionId) {
        return query("""
            select r
            from CourseCompletionRule r
            where r.revisionId = :id
            """, CourseCompletionRule.class, Map.of("id", revisionId));
    }

    public List<CourseAssessment> assessments(Long revisionId) {
        return query("""
            select a
            from CourseAssessment a
            where a.revisionId = :id
            """, CourseAssessment.class, Map.of("id", revisionId));
    }

    public List<LearningGroup> groups(Long orgId) {
        return query("""
            select g
            from LearningGroup g
            where g.ownerOrgId = :id
            order by g.id
            """, LearningGroup.class, Map.of("id", orgId));
    }

    public List<LearningGroupMember> members(Long groupId) {
        return query("""
            select m
            from LearningGroupMember m
            where m.groupId = :id
            and m.leftAt is null
            order by m.userId
            """, LearningGroupMember.class, Map.of("id", groupId));
    }

    public List<CourseShare> shares(Long courseId) {
        return query("""
            select s
            from CourseShare s
            where s.courseId = :id
            order by s.id
            """, CourseShare.class, Map.of("id", courseId));
    }

    public List<CourseShare> activeShares() {
        return query("""
            select s
            from CourseShare s
            where s.status = :status
            """, CourseShare.class, Map.of("status", ShareStatus.ACTIVE));
    }

    public List<CourseAssignment> assignments(Long courseId) {
        return query("""
            select a
            from CourseAssignment a
            where a.courseId = :id
            order by a.id desc
            """, CourseAssignment.class, Map.of("id", courseId));
    }

    public List<CourseAssignment> idempotentAssignment(String actor, String key) {
        return query("""
            select a
            from CourseAssignment a
            where a.assignedBy = :actor
            and a.idempotencyKey = :key
            """, CourseAssignment.class, Map.of("actor", actor, "key", key));
    }

    public List<AssignmentTarget> targets(Long assignmentId) {
        return query("""
            select t
            from AssignmentTarget t
            where t.assignmentId = :id
            order by t.id
            """, AssignmentTarget.class, Map.of("id", assignmentId));
    }

    public List<AssignmentRecipient> recipients(Long assignmentId) {
        return query("""
            select r
            from AssignmentRecipient r
            where r.assignmentId = :id
            order by r.userId
            """, AssignmentRecipient.class, Map.of("id", assignmentId));
    }

    public List<RecipientTargetSource> sources(Long assignmentId) {
        return query("""
            select s
            from RecipientTargetSource s, AssignmentRecipient r
            where s.recipientId = r.id
            and r.assignmentId = :id
            order by s.id
            """, RecipientTargetSource.class, Map.of("id", assignmentId));
    }

    public List<CourseEnrollment> enrollmentHistory(Long courseId, String userId) {
        return query("""
            select e
            from CourseEnrollment e
            where e.courseId = :id
            and e.userId = :user
            order by e.cycleNo desc
            """, CourseEnrollment.class, Map.of("id", courseId, "user", userId));
    }

    public List<AssignmentRecipient> enrollmentRecipients(Long enrollmentId) {
        return query("""
            select r
            from AssignmentRecipient r, CourseAssignment a
            where r.enrollmentId = :id
            and r.assignmentId = a.id
            and r.status = :recipientStatus
            and a.status = :assignmentStatus
            """, AssignmentRecipient.class, Map.of("id", enrollmentId,
                "recipientStatus", RecipientStatus.ACTIVE, "assignmentStatus", AssignmentStatus.ACTIVE));
    }

    public List<LessonProgress> lessonProgress(Long enrollmentId) {
        return query("""
            select p
            from LessonProgress p
            where p.enrollmentId = :id
            order by p.id
            """, LessonProgress.class, Map.of("id", enrollmentId));
    }

    public List<BlockProgress> blockProgress(Long enrollmentId) {
        return query("""
            select p
            from BlockProgress p
            where p.enrollmentId = :id
            order by p.id
            """, BlockProgress.class, Map.of("id", enrollmentId));
    }

    public List<VideoWatchRange> watchRanges(Long progressId) {
        return query("""
            select r
            from VideoWatchRange r
            where r.blockProgressId = :id
            order by r.startSecond
            """, VideoWatchRange.class, Map.of("id", progressId));
    }

    public List<LearningReminder> reminders(Long recipientId) {
        return query("""
            select r
            from LearningReminder r
            where r.recipientId = :id
            order by r.id
            """, LearningReminder.class, Map.of("id", recipientId));
    }

    public List<LearningReminder> dueReminders(String userId) {
        return entityManager.createQuery("""
            select m
            from LearningReminder m, AssignmentRecipient r, CourseAssignment a, CourseEnrollment e
            where m.recipientId = r.id
            and r.assignmentId = a.id
            and r.enrollmentId = e.id
            and r.userId = :user
            and r.status = :recipientStatus
            and a.status = :assignmentStatus
            and e.status <> :completed
            and m.status = :pending
            and m.scheduledAt <= current_timestamp
            order by m.scheduledAt, m.id
            """, LearningReminder.class).setParameter("user", userId)
            .setParameter("recipientStatus", RecipientStatus.ACTIVE)
            .setParameter("assignmentStatus", AssignmentStatus.ACTIVE)
            .setParameter("completed", ProgressStatus.COMPLETED)
            .setParameter("pending", ReminderStatus.PENDING).setMaxResults(100).getResultList();
    }

    public List<BlockAsset> assetUses(Long assetId) {
        return query("""
            select b
            from BlockAsset b
            where b.assetId = :id
            """, BlockAsset.class, Map.of("id", assetId));
    }

    public List<CourseRevision> assetCovers(Long assetId) {
        return query("""
            select r
            from CourseRevision r
            where r.coverAssetId = :id
            """, CourseRevision.class, Map.of("id", assetId));
    }

    public List<CourseEnrollment> userEnrollments(String userId) {
        return query("""
            select e
            from CourseEnrollment e
            where e.userId = :user
            and e.status <> :withdrawn
            order by e.id desc
            """, CourseEnrollment.class, Map.of("user", userId, "withdrawn", ProgressStatus.WITHDRAWN));
    }

    public boolean courseCodeExists(String code) {
        return !query("""
            select c from Course c
            where lower(c.code) = lower(:code)
            """, Course.class, Map.of("code", code)).isEmpty();
    }
}
