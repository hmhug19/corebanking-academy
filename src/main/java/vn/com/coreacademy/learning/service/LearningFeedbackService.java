package vn.com.coreacademy.learning.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.constants.LearningCodes.FeedbackKind;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningFeedbackRequests.RateLesson;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.RatingResult;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.RatingSummary;
import vn.com.coreacademy.learning.entity.LessonFeedback;
import vn.com.coreacademy.learning.repository.LessonFeedbackReadRepository;
import vn.com.coreacademy.learning.repository.LessonFeedbackRepository;
import vn.com.coreacademy.learning.service.LessonAnchors.LessonAccess;

/**
 * Phản hồi của học viên trên bài học: đánh giá sao, nhận xét công khai, câu hỏi giảng viên và báo nội dung. Quyền vào
 * bài như ghi chú: bài phải thuộc phiên bản của một lần học của người gọi, nếu không là LEARNING-NOT_FOUND. Giới hạn
 * và quyền xem do service quyết định, không tin client.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningFeedbackService {
    private final LessonAnchors lessonAnchors;
    private final LessonFeedbackRepository feedback;
    private final LessonFeedbackReadRepository reads;

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
