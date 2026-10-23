package vn.com.coreacademy.learning.controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.service.LearningAssetService;

@RestController
@RequestMapping("/api/v1/learning-assets")
@Tag(name = "Learning assets")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningAssetController {
    private final LearningAssetService assets;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Upload tài nguyên học tập",
        description = """
            Kiểm tra scope, signature và dung lượng trước khi ghi metadata tài nguyên.
            - Video cần durationSeconds, PDF cần pageCount do giảng viên xác nhận từ file gốc.
            - Tệp thật ở kho riêng, không trả storageKey hoặc đường dẫn local cho frontend.
            """
    )
    public BaseResponse<LearningViews.LearningAssetView> upload(@RequestParam @Positive Long ownerOrgId,
        @RequestPart MultipartFile file,
        @RequestParam(required = false) @DecimalMin("0.001") @DecimalMax("86400") BigDecimal durationSeconds,
        @RequestParam(required = false) @Min(1) Integer pageCount) throws IOException {
        return BaseResponse.ok(assets.upload(ownerOrgId, file, durationSeconds, pageCount));
    }

    @GetMapping("/{assetId}/content")
    @ApiResponse(responseCode = "200", description = "Nội dung tệp đầy đủ, không bọc BaseResponse.",
        content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "Một phần nội dung tệp khi gửi Range hợp lệ.",
        content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            schema = @Schema(type = "string", format = "binary")))
    @Operation(
        summary = "Mở/tải tài nguyên có kiểm soát quyền",
        description = """
            Trả file/stream trực tiếp sau khi kiểm tra quyền, hỗ trợ Range của Spring cho video/PDF.
            - Học viên gửi cả blockId và enrollmentId đúng hồ sơ của mình.
            - Không trả BaseResponse khi tải file thành công; lỗi vẫn theo envelope chung.
            """
    )
    public ResponseEntity<Resource> content(@PathVariable @Positive Long assetId,
                                            @RequestParam(required = false) @Positive Long blockId,
                                            @RequestParam(required = false) @Positive Long enrollmentId) {
        var value = assets.download(assetId, blockId, enrollmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.mimeType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(value.name(), StandardCharsets.UTF_8).build().toString())
            .body(value.resource());
    }
}
