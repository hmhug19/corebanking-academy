package vn.com.coreacademy.assessment.controller;

import java.io.IOException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.service.QuestionService;
import vn.com.coreacademy.assessment.service.QuestionTransferService;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/questions", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "QuestionController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class QuestionController {
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final QuestionService questions;
    private final QuestionTransferService transfer;

    @GetMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Danh sách câu hỏi theo chương trình",
        description = """
            Ngân hàng theo programId; quyền biên soạn GLOBAL/ORG trong đơn vị sở hữu chương trình.
            - courseId vẫn được nhận để tương thích client cũ, không bắt buộc khi có programId.
            - Kèm số phương án, số đáp án đúng và số đề đang dùng câu; không trả nội dung phương án.
            """
    )
    public BaseResponse<PageResponse<AssessmentViews.QuestionSummary>> search(
        @RequestParam(required = false) @Positive Long courseId,
        @RequestParam(required = false) @Positive Long programId,
        @Parameter(description = "Tìm trong mã hoặc nội dung, tối đa 100 ký tự.") @RequestParam(required = false)
        String q,
        @RequestParam(required = false) @Positive Long lessonId,
        @RequestParam(required = false) @Positive Long competencyId,
        @Parameter(description = "SINGLE | MULTIPLE") @RequestParam(required = false) String questionType,
        @Parameter(description = "EASY | MEDIUM | HARD") @RequestParam(required = false) String difficulty,
        @Parameter(description = "ACTIVE | INACTIVE") @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @Parameter(description = "id | code | updateTime, kèm ,asc hoặc ,desc") @RequestParam(defaultValue = "id,desc")
        String sort) {
        return BaseResponse.ok(questions.search(courseId, programId, q, lessonId, competencyId, questionType, difficulty,
            status, page, size, sort));
    }

    @GetMapping("/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Chi tiết câu hỏi",
        description = """
            Gồm phương án, đáp án đúng, contentVersion và các đề đang dùng câu; chỉ dành cho người quản lý khóa.
            """
    )
    public BaseResponse<AssessmentViews.QuestionDetail> get(@PathVariable @Positive Long id) {
        return BaseResponse.ok(questions.get(id));
    }

    @PostMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Tạo câu hỏi",
        description = """
            Tạo câu hỏi dùng chung của programId; courseId chỉ dành cho client cũ.
            - Từ 2 phương án; SINGLE đúng 1 đáp án đúng, MULTIPLE ít nhất 2.
            - Mã để trống thì backend sinh Q<ID>; nhóm năng lực phải ACTIVE.
            """
    )
    public BaseResponse<AssessmentViews.QuestionDetail> create(
        @Valid @RequestBody AssessmentRequests.QuestionRequest request) {
        return BaseResponse.ok(questions.create(request));
    }

    @PutMapping("/{id}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Cập nhật câu hỏi",
        description = """
            Thay toàn bộ nội dung và danh sách phương án; gửi expectedVersion là rowVersion vừa đọc.
            - Phương án có id được sửa tại chỗ, không có id là phương án mới, phương án cũ bị bỏ sẽ xóa mềm.
            - Câu đã từng phát đề mà đổi nội dung/đáp án sẽ tăng contentVersion; lượt thi cũ không bị chấm lại.
            """
    )
    public BaseResponse<AssessmentViews.QuestionDetail> update(@PathVariable @Positive Long id,
        @RequestParam @Min(0) Long expectedVersion, @Valid @RequestBody AssessmentRequests.QuestionRequest request) {
        return BaseResponse.ok(questions.update(id, expectedVersion, request));
    }

    @GetMapping(value = "/import-template", produces = {XLSX, MediaType.APPLICATION_JSON_VALUE})
    @Operation(
        summary = "Tải mẫu Excel nhập câu hỏi",
        description = """
            Workbook gồm sheet Instructions (hướng dẫn) và sheet Questions trống với 10 cột của mẫu.
            - Dành cho giảng viên/quản trị; thành công trả tệp .xlsx, lỗi trả envelope chung.
            """
    )
    @ApiResponse(responseCode = "200", description = "Tệp mẫu .xlsx.",
        content = @Content(mediaType = XLSX, schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> template() {
        return excel("question-import-template.xlsx", transfer.template());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Kiểm tra hoặc nhập câu hỏi từ Excel",
        description = """
            Nhập tối đa 1000 câu vào ngân hàng của programId từ tệp .xlsx tối đa 5 MiB (part file).
            - Chọn chương trình ngoài tệp; courseId chỉ dành cho client cũ, không bắt buộc khi có programId.
            - dryRun=true (mặc định) chỉ kiểm tra; false kiểm tra lại và ghi toàn bộ cùng audit trong một transaction.
            - overwrite=true ghi đè câu cùng mã trong cùng chương trình; không ghi đè ngân hàng khác.
            - Có lỗi thì không ghi dòng nào: ASSESSMENT-IMPORT_INVALID, errors.field = Questions[dòng Excel].cột.
            """
    )
    public BaseResponse<AssessmentViews.QuestionImportResult> importFile(
        @RequestParam(required = false) @Positive Long courseId,
        @RequestParam(required = false) @Positive Long programId,
        @RequestPart MultipartFile file, @RequestParam(defaultValue = "true") boolean dryRun,
        @RequestParam(defaultValue = "false") boolean overwrite) throws IOException {
        return BaseResponse.ok(transfer.importFile(courseId, programId, file, dryRun, overwrite));
    }

    @GetMapping(value = "/export", produces = {XLSX, MediaType.APPLICATION_JSON_VALUE})
    @Operation(
        summary = "Xuất ngân hàng câu hỏi ra Excel",
        description = """
            Cùng định dạng tệp nhập để sửa rồi nhập lại với overwrite=true; tối đa 10.000 câu theo bộ lọc danh sách.
            - Thành công trả tệp .xlsx; lỗi (quyền, bộ lọc, vượt giới hạn) trả envelope chung.
            """
    )
    @ApiResponse(responseCode = "200", description = "Tệp .xlsx.",
        content = @Content(mediaType = XLSX, schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> export(@RequestParam @Positive Long courseId,
        @RequestParam(required = false) String q, @RequestParam(required = false) @Positive Long competencyId,
        @RequestParam(required = false) String questionType, @RequestParam(required = false) String difficulty,
        @RequestParam(required = false) String status) {
        return excel("questions.xlsx", transfer.export(courseId, q, competencyId, questionType, difficulty, status));
    }

    private static ResponseEntity<byte[]> excel(String fileName, byte[] content) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(XLSX))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                .toString())
            .body(content);
    }

    @PostMapping("/bulk-status")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Đổi trạng thái câu hỏi hàng loạt",
        description = """
            Đặt ACTIVE/INACTIVE cho tối đa 100 câu; caller phải quản lý mọi ngân hàng chứa các câu.
            - Từ chối cả lô nếu có ID không tồn tại hoặc ngưng sử dụng làm đề đã xuất bản thiếu câu.
            """
    )
    public BaseResponse<AssessmentViews.BulkResult> bulkStatus(
        @Valid @RequestBody AssessmentRequests.BulkStatusRequest request) {
        return BaseResponse.ok(questions.bulkStatus(request));
    }

    @PostMapping("/bulk-delete")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xóa câu hỏi hàng loạt",
        description = """
            Xóa mềm tối đa 100 câu; lượt thi cũ vẫn giữ snapshot.
            - Câu đang nằm trong pool của đề chưa xóa chỉ được ngưng sử dụng (ASSESSMENT-QUESTION_IN_USE).
            """
    )
    public BaseResponse<AssessmentViews.BulkResult> bulkDelete(
        @Valid @RequestBody AssessmentRequests.BulkIdsRequest request) {
        return BaseResponse.ok(questions.bulkDelete(request));
    }
}
