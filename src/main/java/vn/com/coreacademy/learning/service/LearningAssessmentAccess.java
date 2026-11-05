package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.constants.LearningCodes.ActivitySource;
import vn.com.coreacademy.learning.constants.LearningCodes.LessonType;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseEnrollment;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.entity.LessonProgress;
import vn.com.coreacademy.learning.entity.TrainingProgram;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.LearningRepository;

/**
 * Adapter công khai để module Assessment kiểm tra quyền trên khóa học và lần học mà không truy cập repository
 * Learning trực tiếp. Quyền quản lý và điều kiện được học dùng đúng policy của Learning.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningAssessmentAccess {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final LearningAccessPolicy access;
    private final LearningRepository repository;
    private final LearningAssignmentService assignments;
    private final LearningIdentityService identity;
    private final LearningProgressService progress;
    private final LearningActivityService activity;

    public record ManagedCourse(Long id, String code, Long ownerOrgId) {
    }

    public record ManagedProgram(Long id, Long ownerOrgId) {
    }

    /** Ngân hàng chung thuộc đơn vị sở hữu chương trình, không suy quyền từ một khóa học của caller. */
    public ManagedProgram requireManagedProgram(Long programId) {
        TrainingProgram program = repository.require(TrainingProgram.class, programId);
        access.requireManage(access.actor(), program.getOwnerOrgId(), null, null);
        return new ManagedProgram(program.getId(), program.getOwnerOrgId());
    }

    /** Cùng phiên bản mới nhất mà màn biên soạn khóa học đang mở. */
    public Long courseProgramId(Long courseId) {
        return repository.revisions(courseId).stream().findFirst().map(CourseRevision::getProgramId).orElse(null);
    }

    /** Giảng viên chỉ quản lý khóa vẫn được đọc bộ câu hỏi chung của đúng chương trình và đơn vị của khóa đó. */
    public void requireCourseProgramBank(Long courseId, Long programId) {
        ManagedCourse course = requireManagedCourse(courseId);
        TrainingProgram program = repository.require(TrainingProgram.class, programId);
        if (!programId.equals(courseProgramId(courseId)) || !program.getOwnerOrgId().equals(course.ownerOrgId())) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
    }

    /** Lần học hợp lệ của caller tại thời điểm gọi; orgId/teamId là đơn vị hiện tại để chụp vào lượt thi. */
    public record StudyingLearner(String userId, Long orgId, Long teamId, Long enrollmentId, Long revisionId,
                                  String courseTitle) {
    }

    /** Phạm vi đọc báo cáo: global, hoặc tập đơn vị (gồm cây con active) và nhóm được phép xem. */
    public record ReportScope(boolean global, Set<Long> orgIds, Set<Long> teamIds) {
    }

    public Actor actor() {
        return access.actor();
    }

    public Actor requireLearnerActor() {
        Actor actor = access.actor();
        access.requireLearner(actor);
        return actor;
    }

    /**
     * Phạm vi báo cáo của caller, xét role và scope trên cùng grant: SUPER_ADMIN/ADMIN/INSTRUCTOR/REPORT_VIEWER với
     * GLOBAL = toàn hệ thống, ORG = đơn vị và cây con active, TEAM = đúng nhóm. OWN không mở quyền báo cáo; không có
     * grant phù hợp thì từ chối 403 thay vì trả rỗng để FE ẩn menu đúng.
     */
    public ReportScope reportScope() {
        Actor actor = access.actor();
        Set<String> roles = Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN, RoleCodes.INSTRUCTOR,
            RoleCodes.REPORT_VIEWER);
        boolean global = false;
        boolean granted = false;
        Set<Long> orgs = new HashSet<>();
        Set<Long> teams = new HashSet<>();
        for (RoleGrant grant : actor.grants()) {
            if (!roles.contains(grant.roleCode())) {
                continue;
            }
            switch (grant.scope().type()) {
                case GLOBAL -> {
                    global = true;
                    granted = true;
                }
                case ORG -> {
                    orgs.addAll(identity.activeOrgIds(grant.scope().orgId(), true));
                    granted = true;
                }
                case TEAM -> {
                    teams.add(grant.scope().teamId());
                    granted = true;
                }
                case OWN -> {
                    // OWN chỉ cho dữ liệu của chính mình, đã có qua API /me.
                }
            }
        }
        if (!granted) {
            throw new AccessDeniedException("Report scope denied");
        }
        return new ReportScope(global, Set.copyOf(orgs), Set.copyOf(teams));
    }

    /** Đơn vị lọc báo cáo kèm cây con active; đơn vị không tồn tại/ngưng trả lỗi không tìm thấy. */
    public Set<Long> orgTree(Long orgId) {
        return identity.activeOrgIds(orgId, true);
    }

    public Map<Long, String> orgNames(Collection<Long> ids) {
        return ids.isEmpty() ? Map.of() : identity.orgNames(ids);
    }

    public Map<Long, String> teamNames(Collection<Long> ids) {
        return ids.isEmpty() ? Map.of() : identity.teamNames(ids);
    }

    /** Caller phải có quyền quản lý khóa theo role/scope hiện tại (cùng quy tắc với biên soạn khóa học). */
    public ManagedCourse requireManagedCourse(Long courseId) {
        Actor actor = access.actor();
        Course course = repository.require(Course.class, courseId);
        access.requireManage(actor, course);
        return new ManagedCourse(course.getId(), course.getCode(), course.getOwnerOrgId());
    }

    /** Như {@link #requireManagedCourse} nhưng trả kết quả thay vì từ chối, để bên gọi chọn đường kiểm quyền khác. */
    public boolean canManageCourse(Long courseId) {
        Course course = repository.require(Course.class, courseId);
        return access.canManage(access.actor(), course.getOwnerOrgId(), course.getOwnerTeamId(),
            course.getOwnerUserId());
    }

    /** Bài học phải thuộc một phiên bản bất kỳ của khóa; ID bài đổi theo bản nháp nên chỉ dùng để gợi ý ôn tập. */
    public void requireLessonInCourse(Long lessonId, Long courseId) {
        CourseLesson lesson = repository.require(CourseLesson.class, lessonId);
        CourseChapter chapter = repository.require(CourseChapter.class, lesson.getChapterId());
        CourseRevision revision = repository.require(CourseRevision.class, chapter.getRevisionId());
        if (!revision.getCourseId().equals(courseId)) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
    }

    /**
     * Học viên phải có lần học hiện hành (chưa rút) của khóa và đang được phép học: khóa active, danh mục mở và
     * nguồn giao/tự đăng ký còn hiệu lực.
     */
    public StudyingLearner requireStudyingLearner(Long courseId) {
        Actor actor = access.actor();
        access.requireLearner(actor);
        CourseEnrollment current = repository.enrollmentHistory(courseId, actor.person().id()).stream()
            .filter(e -> !ProgressStatus.WITHDRAWN.equals(e.getStatus()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(LearningResponseCode.NOT_AVAILABLE));
        CourseEnrollment enrollment = assignments.requireOwnEnrollment(actor, current.getId(), true);
        CourseRevision revision = repository.require(CourseRevision.class, enrollment.getRevisionId());
        return new StudyingLearner(actor.person().id(), actor.person().orgId(), actor.person().teamId(),
            enrollment.getId(), revision.getId(), revision.getTitle());
    }

    /** Học viên có ít nhất một lần học (chưa rút) của khóa; dùng khi cấp thêm lượt để tránh cấp nhầm người. */
    public boolean hasEnrollment(Long courseId, String userId) {
        return repository.enrollmentHistory(courseId, userId).stream()
            .anyMatch(e -> !ProgressStatus.WITHDRAWN.equals(e.getStatus()));
    }

    /** Tên hiển thị theo lô cho một trang kết quả; mã không tồn tại bị bỏ qua. */
    public Map<String, String> fullNames(Collection<String> userIds) {
        return userIds.isEmpty() ? Map.of() : identity.fullNames(userIds);
    }

    /**
     * Mọi bài học bắt buộc của phiên bản đã nhận đạt 100%; khóa không có bài bắt buộc được coi là đã đủ. Bài quiz
     * không tính vì chính đề này có thể nằm trong khóa: đòi bài quiz xong trước khi được thi sẽ khóa vòng tròn.
     */
    public boolean requiredLessonsCompleted(Long enrollmentId, Long revisionId) {
        Map<Long, BigDecimal> values = repository.lessonProgress(enrollmentId).stream()
            .collect(Collectors.toMap(LessonProgress::getLessonId, LessonProgress::getProgressPercent,
                BigDecimal::max));
        return repository.lessons(revisionId).stream()
            .filter(l -> l.getIsRequired() && LessonType.CONTENT.equals(l.getLessonType()))
            .allMatch(l -> values.getOrDefault(l.getId(), BigDecimal.ZERO).compareTo(HUNDRED) >= 0);
    }

    /**
     * Học viên đạt đề: hoàn thành bài quiz trỏ tới đề trong lần học và tính lại tiến độ khóa. Chạy trong transaction
     * chấm bài của bên gọi; lần học không còn hiệu lực thì bỏ qua (trả false) để điểm không bị mất.
     */
    @Transactional
    public boolean quizPassed(String userId, Long enrollmentId, Long quizId) {
        return progress.completeQuizLessons(userId, enrollmentId, quizId);
    }

    public boolean quizLinked(Long quizId) {
        return repository.quizLinked(quizId);
    }

    /**
     * Ghi ngày học khi học viên bắt đầu hoặc nộp bài thi. Chờ giao dịch của bên gọi commit rồi mới ghi, và lỗi ghi
     * không làm hỏng lượt thi.
     */
    public void recordQuizActivity(String userId) {
        activity.record(userId, ActivitySource.QUIZ);
    }

    /** Bài quiz của phiên bản đã nhận theo đề: quizId → lessonId (đề nằm ở nhiều bài thì lấy bài đầu tiên). */
    public Map<Long, Long> quizLessonIds(Long revisionId) {
        return repository.lessons(revisionId).stream()
            .filter(l -> LessonType.QUIZ.equals(l.getLessonType()) && l.getQuizId() != null)
            .collect(Collectors.toMap(CourseLesson::getQuizId, CourseLesson::getId, (first, second) -> first));
    }
}
