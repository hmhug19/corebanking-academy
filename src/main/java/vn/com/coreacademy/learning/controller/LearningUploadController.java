package vn.com.coreacademy.learning.controller;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.MediaRequests;
import vn.com.coreacademy.learning.dto.MediaViews;
import vn.com.coreacademy.learning.service.LearningUploadService;

@RestController
@RequestMapping(value = "/api/v1/learning-assets", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Tài nguyên học tập")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningUploadController {
    private final LearningUploadService uploads;

    @GetMapping
    @Operation(
        summary = "Tra cứu thư viện tài nguyên",
        description = """
            Tìm tài nguyên trong phạm vi quản lý, phân trang tại database.
            - Không trả key S3, credential hoặc upload ID của provider.
            """
    )
    public BaseResponse<PageResponse<MediaViews.Asset>> list(@RequestParam(required = false) String q,
                                                             @RequestParam(required = false) String mediaType,
                                                             @RequestParam(required = false) String status,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size,
                                                             @RequestParam(defaultValue = "id,desc") String sort) {
        return BaseResponse.ok(uploads.list(q, mediaType, status, page, size, sort));
    }

    @PostMapping("/uploads")
    @Operation(
        summary = "Khởi tạo hoặc tiếp tục phiên tải lên",
        description = """
            Khai báo tên, dung lượng và SHA-256 toàn file để cấp phiên upload 24 giờ.
            - Retry dùng cùng requestId và metadata; chia file thành các phần 8 MiB.
            """
    )
    public BaseResponse<MediaViews.Upload> initiate(@Valid @RequestBody MediaRequests.Upload request) {
        return BaseResponse.ok(uploads.initiate(request));
    }

    @GetMapping("/uploads/{uploadId}")
    @Operation(
        summary = "Xem trạng thái upload và xử lý file",
        description = """
            Trả các phần đã nhận, số byte đã lưu và kết quả kiểm file cho chính người upload.
            - Chỉ ready mới được dùng để xuất bản; đủ byte vẫn có thể đang xử lý.
            """
    )
    public BaseResponse<MediaViews.Upload> status(@PathVariable UUID uploadId) {
        return BaseResponse.ok(uploads.status(uploadId));
    }

    @PutMapping(value = "/uploads/{uploadId}/parts/{partNumber}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(
        summary = "Tải một phần file lên S3",
        description = """
            Gửi bytes kèm header X-Part-SHA256; backend kiểm size/hash trước ACK.
            - Retry phần đã ACK không tăng dung lượng; nội dung khác bị từ chối.
            """
    )
    public BaseResponse<MediaViews.Upload> part(@PathVariable UUID uploadId, @PathVariable @Positive int partNumber,
                                                @RequestHeader("X-Part-SHA256") String checksum,
                                                HttpServletRequest request) throws IOException {
        try (var input = request.getInputStream()) {
            return BaseResponse.ok(uploads.part(uploadId, partNumber, checksum, input));
        }
    }

    @PostMapping("/uploads/{uploadId}/complete")
    @Operation(
        summary = "Hoàn tất upload và yêu cầu kiểm file",
        description = """
            Kiểm manifest, đưa file vào hàng đợi hoàn tất S3 và kiểm hash/codec/số trang.
            - Dùng GET upload để theo dõi processing/ready/failed; chưa ready thì chưa được phát.
            """
    )
    public BaseResponse<MediaViews.Upload> complete(@PathVariable UUID uploadId) {
        return BaseResponse.ok(uploads.complete(uploadId));
    }

    @DeleteMapping("/uploads/{uploadId}")
    @Operation(
        summary = "Hủy phiên tải lên",
        description = """
            Hủy multipart chưa hoàn tất theo yêu cầu của chính người upload.
            - Retry được phép; không dùng để xóa asset đã hoàn tất.
            """
    )
    public BaseResponse<MediaViews.Upload> cancel(@PathVariable UUID uploadId) {
        return BaseResponse.ok(uploads.cancel(uploadId));
    }

    @DeleteMapping("/{assetId}")
    @Operation(
        summary = "Xóa mềm tài nguyên chưa được sử dụng",
        description = """
            Ngừng phục vụ sau khi kiểm quyền và mọi tham chiếu nội dung/tiến độ.
            - Không tự xóa bytes S3; tài nguyên còn được dùng sẽ bị từ chối.
            """
    )
    public BaseResponse<Void> delete(@PathVariable @Positive Long assetId) {
        uploads.delete(assetId);
        return BaseResponse.ok();
    }
}
