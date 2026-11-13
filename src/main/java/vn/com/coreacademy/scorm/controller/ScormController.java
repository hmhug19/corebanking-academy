package vn.com.coreacademy.scorm.controller;

import java.io.IOException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.scorm.dto.ScormDtos;
import vn.com.coreacademy.scorm.service.ScormService;

@RestController
@RequestMapping(value = "/api/v1/scorm/packages", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Tag(name = "SCORM 1.2 lecture library")
@StandardApiErrors
@RequiredArgsConstructor
public class ScormController {
    private final ScormService service;

    @GetMapping
    @Operation(summary = "Danh sách bài giảng SCORM trong phạm vi của người dùng")
    public BaseResponse<ScormDtos.PackagePage> list(@RequestParam(defaultValue = "") @Size(max = 200) String q,
        @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return BaseResponse.ok(service.list(q.strip(), page, size));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Nhập tệp ZIP SCORM 1.2 Single SCO",
        description = "Kiểm scope, manifest, đường dẫn và giới hạn ZIP trước khi lưu vào S3 private. Gói mới là nháp.")
    public BaseResponse<ScormDtos.PackageView> upload(@RequestParam @Positive long ownerOrgId,
        @RequestPart MultipartFile file) throws IOException {
        return BaseResponse.ok(service.upload(ownerOrgId, file));
    }

    @GetMapping("/{id}")
    public BaseResponse<ScormDtos.PackageView> get(@PathVariable @Positive long id,
        @RequestParam(required = false) @Positive Long enrollmentId,
        @RequestParam(required = false) @Positive Long lessonId) {
        return BaseResponse.ok(service.get(id, enrollmentId, lessonId));
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "Xuất bản bài giảng cho học viên trong đơn vị sở hữu và các đơn vị con")
    public BaseResponse<ScormDtos.PackageView> publish(@PathVariable @Positive long id,
        @Valid @RequestBody ScormDtos.VersionRequest request) {
        return BaseResponse.ok(service.publish(id, request.expectedVersion()));
    }

    @GetMapping("/{id}/progress")
    public BaseResponse<ScormDtos.ProgressView> progress(@PathVariable @Positive long id,
        @RequestParam(defaultValue = "false") boolean preview,
        @RequestParam(required = false) @Positive Long enrollmentId,
        @RequestParam(required = false) @Positive Long lessonId) {
        return BaseResponse.ok(service.progress(id, preview, enrollmentId, lessonId));
    }

    @PostMapping("/{id}/launch")
    @Operation(summary = "Mở bài giảng hoặc xem thử trên player origin riêng",
        description = "Mỗi lần mở thay vé cũ; dữ liệu xem thử tách khỏi kết quả học viên. Vé hết hạn/revoke không được ghi.")
    public BaseResponse<ScormDtos.LaunchView> launch(@PathVariable @Positive long id,
        @Valid @RequestBody ScormDtos.LaunchRequest request) {
        return BaseResponse.ok(service.launch(id, request.preview(), request.enrollmentId(), request.lessonId(),
            request.restart()));
    }
}
