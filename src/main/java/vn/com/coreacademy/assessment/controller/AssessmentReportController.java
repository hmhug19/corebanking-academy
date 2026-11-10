package vn.com.coreacademy.assessment.controller;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.service.AssessmentReportService;
import vn.com.coreacademy.assessment.service.AssessmentReportService.Filter;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

/**
 * Báo cáo thi chỉ đọc. Mọi API nhận bộ lọc chung orgId (gồm đơn vị con), teamId, courseId, quizId, from/to (thời điểm
 * nộp, ISO-8601) và chỉ trả dữ liệu trong phạm vi role/scope của caller.
 */
@RestController
@RequestMapping(value = "/api/v1/reports/assessment", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "AssessmentReportController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class AssessmentReportController {
    private final AssessmentReportService reports;

    @GetMapping("/overview")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tổng quan kết quả thi",
        description = """
            Số lượt chấm, học viên, học viên đạt, tỷ lệ đạt và điểm trung bình; chia theo đề và theo đơn vị lúc thi.
            - Đạt/điểm cuối của mỗi học viên theo scoreMethod (BEST/LAST) của từng đề.
            - SUPER_ADMIN/ADMIN/INSTRUCTOR/REPORT_VIEWER; GLOBAL toàn hệ thống, ORG gồm đơn vị con, TEAM đúng nhóm.
            """
    )
    public BaseResponse<AssessmentViews.ReportOverview> overview(
        @RequestParam(required = false) @Positive Long orgId, @RequestParam(required = false) @Positive Long teamId,
        @RequestParam(required = false) @Positive Long courseId, @RequestParam(required = false) @Positive Long quizId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return BaseResponse.ok(reports.overview(new Filter(orgId, teamId, courseId, quizId, from, to)));
    }

    @GetMapping("/quiz-options")
    @Operation(summary = "Danh sách bài kiểm tra để lọc báo cáo", description = """
        Lọc bài kiểm tra theo khóa học, đơn vị và nhóm.
        - Gồm cả bài đã mở nhưng chưa có lượt thi trong phạm vi học viên.
        """)
    public BaseResponse<List<AssessmentViews.ReportQuizOption>> quizOptions(
        @RequestParam(required = false) @Positive Long orgId, @RequestParam(required = false) @Positive Long teamId,
        @RequestParam(required = false) @Positive Long courseId, @RequestParam(required = false) @Positive Long quizId) {
        return BaseResponse.ok(reports.quizOptions(new Filter(orgId, teamId, courseId, quizId, null, null)));
    }

    @GetMapping("/results")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Kết quả từng lượt thi",
        description = """
            Mỗi dòng là một lượt thi trong phạm vi, kèm tên học viên, đơn vị/nhóm lúc thi.
            """
    )
    public BaseResponse<PageResponse<AssessmentViews.ReportResultRow>> results(
        @RequestParam(required = false) @Positive Long orgId, @RequestParam(required = false) @Positive Long teamId,
        @RequestParam(required = false) @Positive Long courseId, @RequestParam(required = false) @Positive Long quizId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @Parameter(description = "Mã cán bộ học viên.") @RequestParam(required = false) String userId,
        @Parameter(description = "IN_PROGRESS | SUBMITTED | AUTO_SUBMITTED | VOIDED") @RequestParam(required = false)
        String status,
        @RequestParam(required = false) Boolean passed,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @Parameter(description = "id | startedAt | submittedAt | scorePercent, kèm ,asc hoặc ,desc")
        @RequestParam(defaultValue = "submittedAt,desc") String sort) {
        return BaseResponse.ok(reports.results(new Filter(orgId, teamId, courseId, quizId, from, to), userId, status,
            passed, page, size, sort));
    }

    @GetMapping("/learners")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tình trạng học viên theo bài kiểm tra",
        description = """
            Mọi học viên có lần học của khóa chứa quizId (bắt buộc), gồm cả người chưa thi.
            - learnerStatus: NOT_STARTED | IN_PROGRESS | FAILED | PASSED; lượt còn lại tính cả lượt cấp thêm.
            - Phạm vi xét theo đơn vị/nhóm lúc ghi danh; from/to không áp dụng.
            """
    )
    public BaseResponse<PageResponse<AssessmentViews.ReportLearnerRow>> learners(
        @RequestParam(required = false) @Positive Long quizId,
        @RequestParam(required = false) @Positive Long orgId, @RequestParam(required = false) @Positive Long teamId,
        @Parameter(description = "NOT_STARTED | IN_PROGRESS | FAILED | PASSED") @RequestParam(required = false)
        String learnerStatus,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @Parameter(description = "userId | finalScore | lastSubmittedAt, kèm ,asc hoặc ,desc")
        @RequestParam(defaultValue = "userId") String sort) {
        return BaseResponse.ok(reports.learners(new Filter(orgId, teamId, null, quizId, null, null), learnerStatus,
            page, size, sort));
    }

    @GetMapping("/competency")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Năng lực theo học viên hoặc đơn vị",
        description = """
            Tổng hợp theo nhóm năng lực trên mọi lượt đã chấm trong phạm vi; groupBy=user (mặc định) hoặc org.
            """
    )
    public BaseResponse<PageResponse<AssessmentViews.ReportCompetencyRow>> competency(
        @RequestParam(defaultValue = "user") String groupBy,
        @RequestParam(required = false) @Positive Long orgId, @RequestParam(required = false) @Positive Long teamId,
        @RequestParam(required = false) @Positive Long courseId, @RequestParam(required = false) @Positive Long quizId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @Parameter(description = "groupKey | competencyId, kèm ,asc hoặc ,desc")
        @RequestParam(defaultValue = "groupKey") String sort) {
        return BaseResponse.ok(reports.competency(new Filter(orgId, teamId, courseId, quizId, from, to), groupBy,
            page, size, sort));
    }

    @GetMapping("/questions")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Phân tích câu hỏi",
        description = """
            Theo (câu hỏi, phiên bản): số lần phát, đúng, bỏ trống và % sai; mặc định câu sai nhiều nhất trước.
            """
    )
    public BaseResponse<PageResponse<AssessmentViews.QuestionStatRow>> questions(
        @RequestParam(required = false) @Positive Long orgId, @RequestParam(required = false) @Positive Long teamId,
        @RequestParam(required = false) @Positive Long courseId, @RequestParam(required = false) @Positive Long quizId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @Parameter(description = "questionId | timesServed | wrongPercent, kèm ,asc hoặc ,desc")
        @RequestParam(defaultValue = "wrongPercent,desc") String sort) {
        return BaseResponse.ok(reports.questions(new Filter(orgId, teamId, courseId, quizId, from, to), page, size,
            sort));
    }

    @GetMapping(value = "/{type}/export", produces = {"text/csv", MediaType.APPLICATION_JSON_VALUE})
    @Operation(
        summary = "Xuất báo cáo thi ra CSV",
        description = """
            type: results | learners | competency | questions; cùng tham số và phạm vi với API danh sách tương ứng.
            - Tối đa 10.000 dòng, UTF-8 BOM; lỗi trả envelope chung.
            """
    )
    public ResponseEntity<byte[]> export(@PathVariable String type,
        @RequestParam(required = false) @Positive Long orgId, @RequestParam(required = false) @Positive Long teamId,
        @RequestParam(required = false) @Positive Long courseId, @RequestParam(required = false) @Positive Long quizId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(required = false) String userId, @RequestParam(required = false) String status,
        @RequestParam(required = false) Boolean passed, @RequestParam(defaultValue = "user") String groupBy,
        @RequestParam(required = false) String learnerStatus, @RequestParam(required = false) String sort) {
        byte[] content = reports.export(type, new Filter(orgId, teamId, courseId, quizId, from, to), userId, status,
            passed, groupBy, learnerStatus, sort);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("assessment-" + type + ".csv").build().toString())
            .body(content);
    }
}
