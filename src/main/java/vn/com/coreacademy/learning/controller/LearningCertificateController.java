package vn.com.coreacademy.learning.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.CertificateView;
import vn.com.coreacademy.learning.service.LearningCertificateService;

@RestController
@RequestMapping(value = "/api/v1/me/learning/certificates", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningCertificateController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningCertificateController {
    private final LearningCertificateService certificates;

    @GetMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Chứng nhận hoàn thành của tôi",
        description = """
            Mỗi lần học đã hoàn thành (status=completed và có completed_at) của người gọi là một chứng nhận, mới hoàn
            thành nhất trước, cùng thời điểm thì id lớn trước.
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            - size tối đa 100.
            - bestScorePercent là điểm cao nhất bài thi cuối khóa như thẻ khóa học; null khi khóa không có bài thi cuối.
            """
    )
    public BaseResponse<PageResponse<CertificateView>> list(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(LearningLimits.MAX_CERTIFICATE_PAGE_SIZE) int size) {
        return BaseResponse.ok(certificates.list(page, size));
    }

    @GetMapping("/{enrollmentId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Một chứng nhận",
        description = """
            Chứng nhận theo ID của lần học (enrollment).
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            - Lần học của người khác, chưa hoàn thành (kể cả withdrawn) hoặc không tồn tại đều là LEARNING-NOT_FOUND.
            - courseTitle là tên của phiên bản đã học xong, không đổi khi khóa ra phiên bản mới.
            """
    )
    public BaseResponse<CertificateView> get(@PathVariable @Positive Long enrollmentId) {
        return BaseResponse.ok(certificates.get(enrollmentId));
    }
}
