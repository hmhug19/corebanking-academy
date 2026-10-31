package vn.com.coreacademy.learning.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressEvent;
import vn.com.coreacademy.learning.constants.LearningCodes.SearchMode;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningProgressOverview;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.dto.MediaRequests;
import vn.com.coreacademy.learning.dto.MediaViews;
import vn.com.coreacademy.learning.service.LearningMediaTokenService;
import vn.com.coreacademy.learning.service.LearningProgressReportService;
import vn.com.coreacademy.learning.service.LearningProgressService;
import vn.com.coreacademy.learning.service.LearningSearchService;
import vn.com.coreacademy.learning.service.LearningSessionService;

@RestController
@RequestMapping(value = "/api/v1/me/learning", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningLearnerController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningLearnerController {
    private final LearningSessionService sessions;
    private final LearningMediaTokenService mediaTokens;
    private final LearningProgressReportService reports;
    private final LearningProgressService progress;
    private final LearningSearchService search;

    @GetMapping("/courses")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
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
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "id,asc") String sort,
        @Parameter(description = "true chỉ lấy khóa quá hạn chưa hoàn thành, false loại các khóa đó.")
        @RequestParam(required = false) Boolean overdue,
        @Parameter(description = "true chỉ lấy khóa đang được giao cho caller (có đợt giao còn hiệu lực), false chỉ "
            + "lấy khóa không được giao.") @RequestParam(required = false) Boolean assigned,
        @Parameter(description = "true chỉ lấy khóa caller đã thi cuối khóa nhưng chưa đạt lượt nào, false loại các "
            + "khóa đó.") @RequestParam(required = false) Boolean failed) {
        return BaseResponse.ok(search.search(SearchMode.MINE, q, fieldId, programId, moduleId, level, requirement,
            learningStatus, state, page, size, sort, overdue, assigned, failed));
    }

    @GetMapping("/catalog")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
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
        @RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "id,asc") String sort,
        @Parameter(description = "true chỉ lấy khóa quá hạn chưa hoàn thành, false loại các khóa đó.")
        @RequestParam(required = false) Boolean overdue,
        @Parameter(description = "true chỉ lấy khóa đang được giao cho caller (có đợt giao còn hiệu lực), false chỉ "
            + "lấy khóa không được giao.") @RequestParam(required = false) Boolean assigned,
        @Parameter(description = "true chỉ lấy khóa caller đã thi cuối khóa nhưng chưa đạt lượt nào, false loại các "
            + "khóa đó.") @RequestParam(required = false) Boolean failed) {
        return BaseResponse.ok(search.search(SearchMode.CATALOG, q, fieldId, programId, moduleId, level, requirement,
            learningStatus, state, page, size, sort, overdue, assigned, failed));
    }

    @GetMapping("/enrollments/{enrollmentId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
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
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Ghi tương tác học và tổng hợp tiến độ",
        description = """
            Gửi sessionId/sessionFence, assetId, eventType và expectedVersion/sequence của phiên đã cấp.
            - Retry đúng event cuối không cộng tiến độ; SEEK không tính đoạn bị tua qua.
            - CLOSE lưu checkpoint cuối và giải phóng phiên; chuyển thiết bị sẽ chặn event phiên cũ.
            """
    )
    public BaseResponse<LearningDetails.ProgressResult> progress(@PathVariable @Positive Long enrollmentId,
        @PathVariable @Positive Long blockId, @Valid @RequestBody LearningRequests.ProgressRequest request,
        HttpServletRequest httpRequest, HttpServletResponse response) {
        var result = progress.progress(enrollmentId, blockId, request);
        mediaTokens.renew(request.sessionId(), request.sessionFence(), ProgressEvent.CLOSE.equals(request.eventType()),
            httpRequest, response);
        return BaseResponse.ok(result);
    }

    @GetMapping("/reminders")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
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
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Đánh dấu đã đọc nhắc học",
        description = """
            Chỉ caller sở hữu người nhận được đánh dấu thông báo đã đọc.
            """
    )
    public BaseResponse<LearningViews.LearningReminderView> read(@PathVariable @Positive Long reminderId) {
        return BaseResponse.ok(progress.dismissReminder(reminderId));
    }

    @PostMapping("/sessions")
    @Operation(
        summary = "Mở hoặc chuyển phiên học",
        description = """
            Cấp một phiên học hoạt động trên tài khoản, trả version và vị trí tiếp tục.
            - Gửi previousSessionId/previousFence khi đổi bài hoặc khôi phục phiên đang giữ.
            - takeover=true sau xác nhận chuyển thiết bị; response đặt cookie media HttpOnly.
            """
    )
    public BaseResponse<MediaViews.Session> session(@Valid @RequestBody MediaRequests.Session request,
                                                    HttpServletRequest httpRequest, HttpServletResponse response) {
        var result = sessions.claim(request);
        mediaTokens.cookie(result, httpRequest, response);
        return BaseResponse.ok(result);
    }

    @GetMapping("/progress/summary")
    @Operation(
        summary = "Tổng quan kết quả học tập cá nhân",
        description = """
            Đếm toàn bộ khóa theo lần học hiện hành của caller, không phụ thuộc trang/tìm kiếm bảng.
            - Activity, điểm kiểm tra và năng lực trả unavailable khi chưa có nguồn thực.
            """
    )
    public BaseResponse<LearningProgressOverview> summary() {
        return BaseResponse.ok(reports.summary());
    }

    @GetMapping(value = "/progress/export", produces = "text/csv")
    @Operation(
        summary = "Xuất tiến độ học tập ra CSV",
        description = """
            Xuất tối đa 10.000 dòng theo cùng bộ lọc và phạm vi với danh sách cá nhân.
            - UTF-8 BOM, xử lý ký tự CSV/công thức bảng tính; không bọc BaseResponse khi thành công.
            """
    )
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) Long fieldId,
                                         @RequestParam(required = false) Long programId,
                                         @RequestParam(required = false) Long moduleId,
                                         @RequestParam(required = false) String level,
                                         @RequestParam(required = false) String requirement,
                                         @RequestParam(required = false) String learningStatus,
                                         @RequestParam(required = false) String state,
                                         @Parameter(description = "true chỉ lấy khóa quá hạn chưa hoàn thành, false loại "
                                             + "các khóa đó.")
                                         @RequestParam(required = false) Boolean overdue,
                                         @Parameter(description = "true chỉ lấy khóa đang được giao cho caller, false "
                                             + "chỉ lấy khóa không được giao.")
                                         @RequestParam(required = false) Boolean assigned,
                                         @Parameter(description = "true chỉ lấy khóa caller đã thi cuối khóa nhưng chưa "
                                             + "đạt lượt nào, false loại các khóa đó.")
                                         @RequestParam(required = false) Boolean failed,
                                         @RequestParam(defaultValue = "id,asc") String sort) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=learning-progress.csv")
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(reports.export(q, fieldId, programId, moduleId, level, requirement, learningStatus, state,
                overdue, assigned, failed, sort));
    }

}
