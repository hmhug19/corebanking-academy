package vn.com.coreacademy.learning.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.constants.LearningCodes.FeedbackKind;
import vn.com.coreacademy.learning.constants.LearningCodes.FeedbackStatus;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningFeedbackRequests.ReplyFeedback;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.FeedbackView;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.InstructorFeedbackSummary;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.InstructorFeedbackView;
import vn.com.coreacademy.learning.dto.LearningNoteViews.Anchor;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.LessonFeedback;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.mapper.LessonFeedbackMapper;
import vn.com.coreacademy.learning.repository.LearningRepository;
import vn.com.coreacademy.learning.repository.LessonFeedbackReadRepository;
import vn.com.coreacademy.learning.repository.LessonFeedbackReadRepository.InstructorRow;
import vn.com.coreacademy.learning.repository.LessonFeedbackReadRepository.ManageScope;
import vn.com.coreacademy.learning.repository.LessonFeedbackRepository;
import vn.com.coreacademy.identity.service.LearningIdentityService;

/**
 * Câu hỏi và báo nội dung của học viên, phía giảng viên: xem trên các khóa mình quản lý và trả lời. Phạm vi xem
 * giống tìm kiếm khóa MANAGE (toàn cục, đơn vị, nhóm hoặc khóa do mình tạo); trả lời kiểm quyền quản lý đúng khóa
 * chứa bài. Số câu hỏi chưa trả lời (status=new) là "thông báo" cho giảng viên: trả lời là đã xử lý, nên không có
 * bảng thông báo riêng. Nhận xét công khai và đánh giá sao không cần trả lời nên không có mặt ở đây.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningInstructorFeedbackService {
    private static final List<String> REPLYABLE_KINDS = List.of(FeedbackKind.QUESTION, FeedbackKind.REPORT);

    private final LearningAccessPolicy access;
    private final LearningIdentityService identity;
    private final LearningRepository repository;
    private final LessonFeedbackRepository feedback;
    private final LessonFeedbackReadRepository reads;
    private final LessonFeedbackMapper mapper;

    /** Trang câu hỏi và báo nội dung của các khóa người gọi quản lý, mới nhất trước; status null là mọi trạng thái. */
    public PageResponse<InstructorFeedbackView> list(String status, int page, int size) {
        ManageScope scope = scope();
        if (scope.isEmpty()) {
            return PageResponse.from(new PageImpl<>(List.of(), PageRequest.of(page, size), 0));
        }
        long total = reads.instructorFeedbackCount(scope, status);
        long offset = (long) page * size;
        List<InstructorRow> rows = offset >= total ? List.of()
            : reads.instructorFeedback(scope, status, (int) offset, size);
        Map<String, String> names = identity.fullNames(rows.stream().map(InstructorRow::userId).distinct().toList());
        List<InstructorFeedbackView> items = rows.stream().map(row -> view(row, names.get(row.userId()))).toList();
        return PageResponse.from(new PageImpl<>(items, PageRequest.of(page, size), total));
    }

    /** Số câu hỏi và báo nội dung chưa trả lời; 0 với người không quản lý khóa nào, như một học viên. */
    public InstructorFeedbackSummary summary() {
        ManageScope scope = scope();
        return new InstructorFeedbackSummary(scope.isEmpty() ? 0
            : reads.instructorFeedbackCount(scope, FeedbackStatus.NEW));
    }

    /**
     * Trả lời một câu hỏi hoặc báo nội dung và đặt answered, hoặc resolved khi giảng viên đóng nó. Trả lời lại thay
     * nội dung cũ. Nhận xét và đánh giá không trả lời được: coi như không tìm thấy.
     */
    @Transactional
    public FeedbackView reply(Long id, ReplyFeedback request) {
        var actor = access.actor();
        LessonFeedback value = feedback.findById(id)
            .filter(found -> !Boolean.TRUE.equals(found.getIsDeleted()) && REPLYABLE_KINDS.contains(found.getKind()))
            .orElseThrow(() -> new BusinessException(LearningResponseCode.NOT_FOUND));
        access.requireManage(actor, repository.require(Course.class, value.getCourseId()));
        String text = replyText(request.reply());

        feedback.lock("lesson-feedback-reply:" + id);
        String userId = actor.person().id();
        value.setReply(text);
        value.setRepliedBy(userId);
        value.setReplyTime(Instant.now());
        value.setStatus(Boolean.TRUE.equals(request.resolve()) ? FeedbackStatus.RESOLVED : FeedbackStatus.ANSWERED);
        value.setUpdatedBy(userId);
        return mapper.view(feedback.saveAndFlush(value));
    }

    private ManageScope scope() {
        var actor = access.actor();
        var scope = access.searchScope(actor);
        return new ManageScope(scope.global(), scope.orgIds(), scope.teamIds(), scope.own(), actor.person().id());
    }

    private static InstructorFeedbackView view(InstructorRow row, String authorName) {
        return new InstructorFeedbackView(row.id(), row.kind(), row.text(),
            new Anchor(row.anchorType(), row.blockId(), row.pdfPage(), row.videoSecond()), row.status(), row.reply(),
            row.replyTime(), row.createTime(), row.lessonId(), row.lessonTitle(), row.courseId(), row.courseCode(),
            row.courseTitle(), authorName);
    }

    /** Nội dung sau khi cắt khoảng trắng đầu cuối phải dài 1 đến 1000 ký tự. */
    private static String replyText(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) {
            throw BusinessException.validation("reply", "REQUIRED", "Nội dung trả lời không được để trống.");
        }
        if (text.length() > LearningLimits.MAX_FEEDBACK_LENGTH) {
            throw BusinessException.validation("reply", "INVALID_LENGTH",
                "Nội dung tối đa %d ký tự.".formatted(LearningLimits.MAX_FEEDBACK_LENGTH));
        }
        return text;
    }
}
