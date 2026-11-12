package vn.com.coreacademy.assistant.conversation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assistant.conversation.ConversationViews.SharedFeedbackView;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.identity.service.AuthService;

@RestController
@RequestMapping(value = "/api/v1/assistant/feedback-review", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assistant feedback review")
@StandardApiErrors
@RequiredArgsConstructor
public class FeedbackReviewController {
    /** Granted to no seeded role; an operator grants it to named reviewers. */
    public static final String FUNCTION = "AI_FEEDBACK_REVIEW";

    private final AuthService auth;
    private final FeedbackReviewService review;

    @GetMapping
    @Operation(summary = "Phản hồi 👎 được người dùng chia sẻ",
        description = "Cần chức năng AI_FEEDBACK_REVIEW (403 nếu thiếu). Chỉ gồm các lượt mà chủ hội thoại tick gửi "
            + "kèm, mới nhất trước; lọc theo reason (lý do) và skill. Không trả id hội thoại hay người dùng, và "
            + "không có đường nào tới phần còn lại của hội thoại.")
    public BaseResponse<PageResponse<SharedFeedbackView>> list(
        @RequestParam(required = false) String reason, @RequestParam(required = false) String skill,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        requireReviewer();
        return BaseResponse.ok(review.list(reason, skill, page, size));
    }

    @GetMapping(value = "/export", produces = {"text/csv", MediaType.APPLICATION_JSON_VALUE})
    @Operation(summary = "Xuất phản hồi 👎 được chia sẻ ra CSV",
        description = "Cùng bộ lọc và quyền như danh sách; tối đa 10.000 dòng, UTF-8 BOM.")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String reason,
                                         @RequestParam(required = false) String skill) {
        requireReviewer();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("assistant-feedback-review.csv").build().toString())
            .body(review.export(reason, skill));
    }

    private void requireReviewer() {
        if (!auth.me().functions().contains(FUNCTION)) {
            throw new AccessDeniedException("The feedback review function is not granted");
        }
    }
}
