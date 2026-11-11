package vn.com.coreacademy.learning.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.FieldViolation;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.learning.constants.LearningCodes.EnrollmentMode;
import vn.com.coreacademy.learning.constants.LearningCodes.ShareStatus;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.repository.LearningRepository;

/**
 * Xuất bản kèm chia sẻ và giao học trong một transaction: xuất bản, rồi từng đơn vị được chia sẻ, rồi từng dòng giao.
 * Các bước con dùng lại {@link LearningContentService#publish} và {@link LearningAssignmentService}; chúng tham gia
 * transaction này (propagation mặc định), nên một lỗi ở bất kỳ dòng nào hủy cả việc xuất bản.
 */
@Service
@RequiredArgsConstructor
public class LearningPublishService {
    private final LearningContentService content;
    private final LearningAssignmentService assignments;
    private final LearningRepository repository;

    @Transactional
    public LearningDetails.CourseDetail publish(Long courseId, Long revisionId,
                                                LearningRequests.PublishRequest request) {
        // Bước 1: xuất bản như cũ; version cũ hoặc nội dung chưa đủ sẽ dừng ở đây, chưa ghi chia sẻ nào.
        var detail = content.publish(courseId, revisionId,
            new LearningRequests.VersionRequest(request.expectedVersion()),
            Boolean.TRUE.equals(request.reviewConfirmed()));

        // Bước 2: chia sẻ rồi giao học theo thứ tự yêu cầu; lỗi ở dòng nào cũng hủy cả transaction.
        Course course = repository.require(Course.class, courseId);
        shareAll(course, request.shareOrgIds());
        assignAll(course, repository.require(CourseRevision.class, revisionId), request.assignments());
        return detail;
    }

    private void shareAll(Course course, List<Long> orgIds) {
        for (int index = 0; index < orgIds.size(); index++) {
            Long orgId = orgIds.get(index);
            if (hasActiveShare(course.getId(), orgId)) {
                continue;
            }
            try {
                assignments.share(course.getId(), new LearningRequests.ShareRequest(orgId, true, true, false, true));
            } catch (BusinessException | AccessDeniedException exception) {
                throw rowFailure("shareOrgIds[" + index + "]", exception);
            }
        }
    }

    /**
     * Mỗi dòng thành một lượt giao qua {@link LearningAssignmentService#assign}, mọi quy tắc của lượt giao đều áp dụng.
     * Khóa idempotency theo bản và chỉ số dòng nên lượt xuất bản bị hủy rồi gửi lại không va chạm với lần trước.
     */
    private void assignAll(Course course, CourseRevision revision, List<LearningRequests.PublishAssignment> rows) {
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            var assignment = new LearningRequests.AssignmentRequest(revision.getId(), course.getOwnerOrgId(), null,
                row.startAt(), row.dueAt(), revision.getDefaultRequirement(), EnrollmentMode.CONTINUE,
                row.reminderEnabled(), row.reminderDaysBefore(), "publish-" + revision.getId() + "-" + index,
                List.of(row.target()));
            try {
                assignments.assign(course.getId(), assignment);
            } catch (BusinessException | AccessDeniedException exception) {
                throw rowFailure("assignments[" + index + "]", exception);
            }
        }
    }

    /** Đơn vị đã được chia sẻ đang hoạt động thì giữ nguyên cấu hình hiện có, không ghi lại và không ghi audit. */
    private boolean hasActiveShare(Long courseId, Long orgId) {
        return repository.shares(courseId).stream()
            .anyMatch(s -> s.getOrgId().equals(orgId) && ShareStatus.ACTIVE.equals(s.getStatus()));
    }

    /**
     * Gắn lỗi của một dòng vào đúng phần tử request, giữ mã và thông báo gốc; ném ra sẽ hủy transaction.
     * Lỗi quyền với người nhận được trả như lỗi của dòng đó, không phải 403 trần.
     */
    static BusinessException rowFailure(String field, RuntimeException cause) {
        if (cause instanceof BusinessException business) {
            FieldViolation original = business.getErrors().isEmpty() ? null : business.getErrors().get(0);
            String code = original != null ? original.code() : business.getResponseCode().getCode();
            String message = original != null ? original.message() : business.getResponseCode().getMessage();
            return BusinessException.validation(field, code, message);
        }
        return BusinessException.validation(field, ResponseCode.FORBIDDEN.getCode(),
            ResponseCode.FORBIDDEN.getMessage());
    }
}
