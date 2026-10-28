package vn.com.coreacademy.community.controller;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.MediaDtos.PartAck;
import vn.com.coreacademy.community.dto.MediaDtos.UploadInit;
import vn.com.coreacademy.community.dto.MediaDtos.UploadSummaryView;
import vn.com.coreacademy.community.dto.MediaDtos.UploadView;
import vn.com.coreacademy.community.service.CommunityUploadService;
import vn.com.coreacademy.storage.web.ObjectDownloadHandler;

@RestController
@RequestMapping("/api/v1/community")
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community media")
public class CommunityMediaController {
    private final CommunityUploadService uploads;
    private final ObjectDownloadHandler downloads;

    @PostMapping(value = "/assets/uploads", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Khởi tạo tải tài nguyên cộng đồng")
    @ApiResponse(responseCode = "200", description = "Phiên upload của caller, không có key S3.",
        useReturnTypeSchema = true)
    public BaseResponse<UploadView> initiate(@Valid @RequestBody UploadInit request) {
        return BaseResponse.ok(uploads.initiate(request));
    }

    @GetMapping(value = "/assets/uploads/{uploadId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Xem tiến độ tải tài nguyên")
    @ApiResponse(responseCode = "200", description = "Manifest sau kiểm uploader và quyền nguồn.",
        useReturnTypeSchema = true)
    public BaseResponse<UploadView> status(@PathVariable UUID uploadId) {
        return BaseResponse.ok(uploads.status(uploadId));
    }

    @PutMapping(value = "/assets/uploads/{uploadId}/parts/{partNumber}",
        consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Tải một phần tài nguyên")
    @ApiResponse(responseCode = "200", description = "ACK sau xác minh byte/hash và reservation.",
        useReturnTypeSchema = true)
    public BaseResponse<PartAck> part(@PathVariable UUID uploadId, @PathVariable @Positive int partNumber,
        @RequestHeader("X-Part-SHA256") String checksum,
        HttpServletRequest request) throws IOException {
        try (var input = request.getInputStream()) {
            return BaseResponse.ok(uploads.part(uploadId, partNumber, checksum, input));
        }
    }

    @PostMapping(value = "/assets/uploads/{uploadId}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Hoàn tất manifest tải tài nguyên")
    @ApiResponse(responseCode = "200", description = "Đưa vào hàng đợi kiểm tệp, chưa đồng nghĩa ready.",
        useReturnTypeSchema = true)
    public BaseResponse<UploadView> complete(@PathVariable UUID uploadId) {
        return BaseResponse.ok(uploads.complete(uploadId));
    }

    @DeleteMapping(value = "/assets/uploads/{uploadId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Hủy phiên tải tài nguyên")
    @ApiResponse(responseCode = "200", description = "Chỉ hủy uploading, không xóa tệp còn tham chiếu.",
        useReturnTypeSchema = true)
    public BaseResponse<UploadView> abort(@PathVariable UUID uploadId) {
        return BaseResponse.ok(uploads.abort(uploadId));
    }

    @GetMapping("/revision-assets/{revisionAssetId}/content")
    @Operation(summary = "Đọc tệp của phiên bản nội dung", description = """
        Trả byte sau kiểm ACL tại mỗi request.
        - Range hợp lệ trả 206; sai hoặc nhiều range trả 416.
        """)
    @ApiResponse(responseCode = "200", description = "Byte tệp đã kiểm tra.", content = @Content(
        mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE, schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "206", description = "Một byte range hợp lệ.")
    @ApiResponse(responseCode = "416", description = "Byte range không hợp lệ.")
    public void content(@PathVariable @Positive Long revisionAssetId, HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        downloads.write(uploads.download(revisionAssetId), request, response);
    }

    @RequestMapping(value = "/revision-assets/{revisionAssetId}/content", method = RequestMethod.HEAD)
    @Operation(summary = "Đọc metadata HTTP của tệp")
    @ApiResponse(responseCode = "200", description = "Header tệp sau kiểm ACL; không có response body.")
    public void head(@PathVariable @Positive Long revisionAssetId, HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        downloads.write(uploads.download(revisionAssetId), request, response);
    }

    @GetMapping(value = "/me/assets", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Xem các phiên tải tài nguyên của tôi")
    @ApiResponse(responseCode = "200", description = "Tài nguyên caller còn quyền theo nguồn.",
        useReturnTypeSchema = true)
    public BaseResponse<PageResponse<UploadSummaryView>> own(@RequestParam(required = false) String status,
        @RequestParam(required = false) String mediaType, @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(uploads.list(status, mediaType, q, page, size));
    }
}
