package vn.com.coreacademy.learning.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.service.LearningProgressService;
import vn.com.coreacademy.learning.service.LearningSearchService;

@RestController
@RequestMapping(value = "/api/v1/me/learning", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningLearnerController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningLearnerController {
    private final LearningProgressService progress;
    private final LearningSearchService search;

    @GetMapping("/courses")
    @Operation(
        summary = "Khóa học của tôi",
        description = """
            Một thẻ mỗi khóa ở lần học hiện hành; tính nghĩa vụ và hạn từ giao học thực tế.
            """
    )
    public BaseResponse<PageResponse<LearningDetails.CourseCard>> mine(@RequestParam(required = false) String q,
        @RequestParam(required = false) Long fieldId, @RequestParam(required = false) Long programId,
        @RequestParam(required = false) Long moduleId, @RequestParam(required = false) String level,
        @RequestParam(required = false) String requirement, @RequestParam(required = false) String learningStatus,
        @RequestParam(required = false) String state, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "id,asc") String sort) {
        return BaseResponse.ok(search.search("mine", q, fieldId, programId, moduleId, level, requirement,
            learningStatus, state, page, size, sort));
    }

    @GetMapping("/catalog")
    @Operation(
        summary = "Danh mục khóa học của học viên",
        description = """
            Bao gồm khóa được giao và khóa được phép khám phá chưa đăng ký.
            """
    )
    public BaseResponse<PageResponse<LearningDetails.CourseCard>> catalog(@RequestParam(required = false) String q,
        @RequestParam(required = false) Long fieldId, @RequestParam(required = false) Long programId,
        @RequestParam(required = false) Long moduleId, @RequestParam(required = false) String level,
        @RequestParam(required = false) String requirement, @RequestParam(required = false) String learningStatus,
        @RequestParam(required = false) String state, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "id,asc") String sort) {
        return BaseResponse.ok(search.search("catalog", q, fieldId, programId, moduleId, level, requirement,
            learningStatus, state, page, size, sort));
    }

    @GetMapping("/enrollments/{enrollmentId}")
    @Operation(
        summary = "Xem nội dung và vị trí tiếp tục học",
        description = """
            Chỉ hồ sơ học của caller; trả cây revision đã nhận và tiến độ từng khối.
            """
    )
    public BaseResponse<LearningDetails.EnrollmentDetail> enrollment(@PathVariable @Positive Long enrollmentId) {
        return BaseResponse.ok(progress.get(enrollmentId));
    }

    @PutMapping("/enrollments/{enrollmentId}/blocks/{blockId}/progress")
    @Operation(
        summary = "Ghi tương tác học và tổng hợp tiến độ",
        description = """
            Gửi expectedVersion/sequence; backend tự tính tỷ lệ, kiểm tra trang PDF và khoảng xem video.
            """
    )
    public BaseResponse<LearningDetails.ProgressResult> progress(@PathVariable @Positive Long enrollmentId,
        @PathVariable @Positive Long blockId, @Valid @RequestBody LearningRequests.ProgressRequest request) {
        return BaseResponse.ok(progress.progress(enrollmentId, blockId, request));
    }

    @GetMapping("/reminders")
    @Operation(
        summary = "Thông báo nhắc học đến hạn",
        description = """
            Thông báo trong ứng dụng, chỉ người nhận đang cần học được thấy.
            """
    )
    public BaseResponse<List<LearningViews.LearningReminderView>> reminders() {
        return BaseResponse.ok(progress.reminders());
    }

    @PostMapping("/reminders/{reminderId}/read")
    @Operation(
        summary = "Đánh dấu đã đọc nhắc học",
        description = """
            Chỉ caller sở hữu người nhận được đánh dấu thông báo đã đọc.
            """
    )
    public BaseResponse<LearningViews.LearningReminderView> read(@PathVariable @Positive Long reminderId) {
        return BaseResponse.ok(progress.dismissReminder(reminderId));
    }

}
