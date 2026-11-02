package vn.com.coreacademy.learning.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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
import vn.com.coreacademy.learning.dto.LearningFeedbackRequests.RateLesson;
import vn.com.coreacademy.learning.dto.LearningFeedbackRequests.SendFeedback;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.CommentView;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.FeedbackView;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.LessonFeedbackView;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.RatingResult;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.RatingSummary;
import vn.com.coreacademy.learning.dto.LearningNoteViews.Anchor;
import vn.com.coreacademy.learning.entity.LessonFeedback;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.mapper.LessonFeedbackMapper;
import vn.com.coreacademy.learning.repository.LessonFeedbackReadRepository;
import vn.com.coreacademy.learning.repository.LessonFeedbackReadRepository.CommentRow;
import vn.com.coreacademy.learning.repository.LessonFeedbackRepository;
import vn.com.coreacademy.learning.service.LessonAnchors.LessonAccess;
import vn.com.coreacademy.learning.service.LessonAnchors.Resolved;

/**
 * Phản hồi của học viên trên bài học: đánh giá sao, nhận xét công khai, câu hỏi giảng viên và báo nội dung. Quyền vào
 * bài như ghi chú: bài phải thuộc phiên bản của một lần học của người gọi, nếu không là LEARNING-NOT_FOUND. Giới hạn
 * và quyền xem do service quyết định, không tin client.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningFeedbackService {
    private static final List<String> SENDABLE_KINDS = List.of(FeedbackKind.COMMENT, FeedbackKind.QUESTION,
        FeedbackKind.REPORT);
    private static final List<String> PRIVATE_KINDS = List.of(FeedbackKind.QUESTION, FeedbackKind.REPORT);

    private final LessonAnchors lessonAnchors;
    private final LessonFeedbackRepository feedback;
    private final LessonFeedbackReadRepository reads;
    private final LessonFeedbackMapper mapper;

    /**
     * Màn phản hồi của bài: điểm đánh giá, số sao của người gọi, trang đầu nhận xét và câu hỏi, báo nội dung của
     * chính người gọi. Câu hỏi và báo nội dung của người khác không bao giờ có mặt ở đây.
     */
    public LessonFeedbackView feedback(Long lessonId) {
        String userId = lessonAnchors.requireLessonAccess(lessonId).userId();
        Integer myStars = feedback
            .findFirstByUserIdAndLessonIdAndKindAndIsDeletedFalseOrderByIdDesc(userId, lessonId, FeedbackKind.RATING)
            .map(LessonFeedback::getStars).orElse(null);
        List<FeedbackView> mine = feedback
            .findByUserIdAndLessonIdAndKindInAndIsDeletedFalseOrderByCreateTimeDescIdDesc(userId, lessonId,
                PRIVATE_KINDS).stream().map(mapper::view).toList();
        return new LessonFeedbackView(reads.lessonRating(lessonId), myStars,
            commentPage(userId, lessonId, 0, LearningLimits.FEEDBACK_PAGE_SIZE), mine);
    }

    /** Trang nhận xét công khai của bài, mới nhất trước, cho nút xem thêm. */
    public PageResponse<CommentView> comments(Long lessonId, int page, int size) {
        String userId = lessonAnchors.requireLessonAccess(lessonId).userId();
        return commentPage(userId, lessonId, page, size);
    }

    /**
     * Gửi nhận xét, câu hỏi hoặc báo nội dung. Thứ tự kiểm: quyền vào bài, nội dung, vị trí neo, rồi giới hạn mỗi
     * ngày dưới khóa advisory của người dùng để các lần gửi đồng thời không cùng vượt giới hạn. Nhận xét không có
     * trạng thái; câu hỏi và báo nội dung bắt đầu ở new.
     */
    @Transactional
    public FeedbackView send(Long lessonId, SendFeedback request) {
        LessonAccess access = lessonAnchors.requireLessonAccess(lessonId);
        String userId = access.userId();
        String text = feedbackText(request.text());
        Resolved resolved = lessonAnchors.resolve(lessonId, request.anchor(), true);

        feedback.lock("lesson-feedback:" + userId);
        if (sentToday(userId, lessonId) >= LearningLimits.MAX_FEEDBACK_PER_LESSON_PER_DAY) {
            throw new BusinessException(LearningResponseCode.FEEDBACK_LIMIT);
        }

        Anchor anchor = resolved.anchor();
        LessonFeedback sent = new LessonFeedback();
        sent.setUserId(userId);
        sent.setLessonId(lessonId);
        sent.setCourseId(access.courseId());
        sent.setKind(request.kind());
        sent.setBody(text);
        sent.setBlockId(anchor.blockId());
        sent.setAnchorType(anchor.type());
        sent.setPdfPage(anchor.page());
        sent.setVideoSecond(anchor.second());
        sent.setStatus(FeedbackKind.COMMENT.equals(request.kind()) ? null : FeedbackStatus.NEW);
        sent.setCreatedBy(userId);
        sent.setUpdatedBy(userId);
        return mapper.view(feedback.saveAndFlush(sent));
    }

    /**
     * Đặt số sao của người gọi cho bài: sửa đánh giá sống nếu có, chưa có thì thêm. Bảng không có UNIQUE nên "một
     * đánh giá sống mỗi người và bài" do khóa advisory của người dùng bảo đảm: hai PUT đồng thời được xếp hàng, lần
     * sau thấy đánh giá của lần trước. Trả điểm trung bình mới của bài để giao diện không phải tải lại.
     */
    @Transactional
    public RatingResult rate(Long lessonId, RateLesson request) {
        LessonAccess access = lessonAnchors.requireLessonAccess(lessonId);
        String userId = access.userId();
        int stars = stars(request.stars());

        feedback.lock("lesson-rating:" + userId);
        LessonFeedback rating = feedback
            .findFirstByUserIdAndLessonIdAndKindAndIsDeletedFalseOrderByIdDesc(userId, lessonId,
                FeedbackKind.RATING)
            .orElseGet(() -> newRating(userId, lessonId, access.courseId()));
        rating.setStars(stars);
        rating.setUpdatedBy(userId);
        feedback.saveAndFlush(rating);

        RatingSummary summary = reads.lessonRating(lessonId);
        return new RatingResult(summary.average(), summary.count(), stars);
    }

    /**
     * Số lần người gọi đã gửi nhận xét, câu hỏi và báo nội dung trên bài trong ngày học hiện tại theo giờ Việt Nam,
     * không theo UTC: 00:30 sáng là ngày mới. Đánh giá sao không tính vì được sửa tại chỗ.
     */
    private long sentToday(String userId, Long lessonId) {
        LocalDate today = LocalDate.now(LearningActivityService.ZONE);
        Instant from = today.atStartOfDay(LearningActivityService.ZONE).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(LearningActivityService.ZONE).toInstant();
        return feedback.countSent(userId, lessonId, SENDABLE_KINDS, from, to);
    }

    private PageResponse<CommentView> commentPage(String userId, Long lessonId, int page, int size) {
        long total = reads.commentCount(lessonId);
        long offset = (long) page * size;
        List<CommentView> items = offset >= total ? List.of()
            : reads.comments(lessonId, (int) offset, size).stream().map(row -> comment(row, userId)).toList();
        return PageResponse.from(new PageImpl<>(items, PageRequest.of(page, size), total));
    }

    private static CommentView comment(CommentRow row, String userId) {
        return new CommentView(row.id(), row.authorName(), row.text(),
            new Anchor(row.anchorType(), row.blockId(), row.pdfPage(), row.videoSecond()), row.createTime(),
            userId.equals(row.userId()));
    }

    /**
     * Nội dung sau khi cắt khoảng trắng đầu cuối phải dài 5 đến 1000 ký tự; trống cũng là quá ngắn. Thông điệp lỗi
     * theo giao diện mẫu để màn hình hiện thẳng dưới ô nhập.
     */
    private static String feedbackText(String raw) {
        String text = raw.trim();
        if (text.length() < LearningLimits.MIN_FEEDBACK_LENGTH) {
            throw BusinessException.validation("text", "INVALID_LENGTH",
                "Nội dung cần tối thiểu %d ký tự.".formatted(LearningLimits.MIN_FEEDBACK_LENGTH));
        }
        if (text.length() > LearningLimits.MAX_FEEDBACK_LENGTH) {
            throw BusinessException.validation("text", "INVALID_LENGTH",
                "Nội dung tối đa %d ký tự.".formatted(LearningLimits.MAX_FEEDBACK_LENGTH));
        }
        return text;
    }

    /** Kiểm ở service để lỗi mang đúng thông điệp của giao diện thay vì thông điệp chung của bean validation. */
    private static int stars(int value) {
        if (value < LearningLimits.MIN_STARS || value > LearningLimits.MAX_STARS) {
            throw BusinessException.validation("stars", "OUT_OF_RANGE", "Số sao phải từ 1 đến 5.");
        }
        return value;
    }

    private static LessonFeedback newRating(String userId, Long lessonId, Long courseId) {
        LessonFeedback rating = new LessonFeedback();
        rating.setUserId(userId);
        rating.setLessonId(lessonId);
        rating.setCourseId(courseId);
        rating.setKind(FeedbackKind.RATING);
        rating.setCreatedBy(userId);
        return rating;
    }
}
