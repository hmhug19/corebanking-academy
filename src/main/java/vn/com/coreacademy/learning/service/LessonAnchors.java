package vn.com.coreacademy.learning.service;

import java.math.RoundingMode;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.constants.LearningCodes.AnchorType;
import vn.com.coreacademy.learning.dto.LearningNoteRequests.AnchorRequest;
import vn.com.coreacademy.learning.dto.LearningNoteViews.Anchor;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.repository.LessonAnchorRepository;
import vn.com.coreacademy.learning.repository.LessonAnchorRepository.BlockBounds;

/**
 * Quyền vào bài học và kiểm vị trí neo dùng chung cho ghi chú, đánh dấu và phản hồi, để ba nơi cùng một luật. Người
 * dùng luôn là học viên đang đăng nhập; vị trí neo luôn được kiểm trên tài nguyên thật của khối, không tin giá trị
 * client gửi.
 */
@Component
@RequiredArgsConstructor
public class LessonAnchors {
    private final LearningAccessPolicy access;
    private final LessonAnchorRepository anchors;

    /** Người gọi và khóa học chứa bài mà họ được vào. */
    public record LessonAccess(String userId, Long courseId) {
    }

    /** Vị trí neo đã kiểm; block là null khi neo là cả bài. */
    public record Resolved(Anchor anchor, BlockBounds block) {
    }

    /** Mã người gọi; chỉ học viên (LEARNER, OWN), vai trò khác nhận 403. */
    public String learnerId() {
        var actor = access.actor();
        access.requireLearner(actor);
        return actor.person().id();
    }

    /**
     * Bài phải thuộc phiên bản của một lần học của người gọi, bất kể tiến độ (chưa học, đang học hay đã hoàn thành
     * đều được); lần học đã rút (withdrawn) không tính. Ngoài ra là NOT_FOUND.
     */
    public LessonAccess requireLessonAccess(Long lessonId) {
        String userId = learnerId();
        Long courseId = anchors.visibleCourseId(userId, lessonId)
            .orElseThrow(() -> new BusinessException(LearningResponseCode.NOT_FOUND));
        return new LessonAccess(userId, courseId);
    }

    /**
     * Kiểm vị trí neo. Thiếu hoặc thừa trường theo loại neo là lỗi dữ liệu đầu vào (CORE-VALIDATION_FAILED). Khối
     * không thuộc bài, sai loại, hoặc vị trí ngoài giới hạn của tài nguyên là LEARNING-INVALID_REFERENCE: trang từ 1
     * đến page_count, giây từ 0 đến phần nguyên của duration_seconds; tài nguyên thiếu giới hạn thì từ chối loại neo
     * đó thay vì nhận mọi giá trị.
     */
    public Resolved resolve(Long lessonId, AnchorRequest request, boolean allowNone) {
        String type = request.type();
        if (AnchorType.NONE.equals(type)) {
            if (!allowNone) {
                throw BusinessException.validation("anchor.type", "INVALID_VALUE", "Loại neo không hợp lệ.");
            }
            if (request.blockId() != null || request.page() != null || request.second() != null) {
                throw BusinessException.validation("anchor", "INVALID_VALUE", "Neo cả bài không có khối hay vị trí.");
            }
            return new Resolved(new Anchor(AnchorType.NONE, null, null, null), null);
        }

        boolean pdf = AnchorType.PDF.equals(type);
        Integer position = pdf ? request.page() : request.second();
        String positionField = pdf ? "anchor.page" : "anchor.second";
        if (request.blockId() == null) {
            throw BusinessException.validation("anchor.blockId", "REQUIRED", "Thông tin này là bắt buộc.");
        }
        if (position == null) {
            throw BusinessException.validation(positionField, "REQUIRED", "Thông tin này là bắt buộc.");
        }
        if ((pdf ? request.second() : request.page()) != null) {
            throw BusinessException.validation(pdf ? "anchor.second" : "anchor.page", "INVALID_VALUE",
                "Giá trị không áp dụng cho loại neo này.");
        }

        BlockBounds block = anchors.block(lessonId, request.blockId())
            .filter(found -> type.equals(found.blockType()))
            .orElseThrow(() -> new BusinessException(LearningResponseCode.INVALID_REFERENCE));
        boolean bounded = pdf ? block.pageCount() != null : block.durationSeconds() != null;
        if (!bounded) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        int min = pdf ? 1 : 0;
        int max = pdf ? block.pageCount() : block.durationSeconds().setScale(0, RoundingMode.FLOOR).intValue();
        if (position < min || position > max) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        return new Resolved(new Anchor(type, request.blockId(), pdf ? position : null, pdf ? null : position),
            block);
    }
}
