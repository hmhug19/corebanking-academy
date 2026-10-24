package vn.com.coreacademy.organization.controller;

import java.io.IOException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.organization.dto.OrganizationImportResult;
import vn.com.coreacademy.organization.service.OrganizationImportService;

@RestController
@RequestMapping("/api/v1/orgs")
@Tag(name = "Organizations")
@StandardApiErrors
@RequiredArgsConstructor
public class OrganizationImportController {
    private final OrganizationImportService imports;

    @GetMapping("/import-template")
    @Operation(
        summary = "Tải mẫu Excel nhập đơn vị, phòng và nhóm",
        description = """
            Tải workbook gồm hướng dẫn và hai sheet dữ liệu trống Organizations, Teams.
            - Mã nhập dạng văn bản; ví dụ nằm riêng trong Instructions, không tự nhập vào database.
            - Yêu cầu quyền quản trị; lỗi trả envelope chung, thành công trả tệp Excel.
            """
    )
    @ApiResponse(responseCode = "200", description = "Tệp mẫu .xlsx.",
        content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            schema = @Schema(type = "string", format = "binary")))
    public ResponseEntity<byte[]> template() throws IOException {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("organization-import-template.xlsx").build().toString())
            .body(imports.template());
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Kiểm tra hoặc nhập đơn vị, phòng và nhóm từ Excel",
        description = """
            Nhập mới tối đa 1000 dòng từ tệp .xlsx tối đa 5 MiB; không cập nhật bản ghi đã tồn tại.
            - dryRun=true mặc định chỉ kiểm tra; false kiểm tra lại và ghi toàn bộ cùng audit trong một transaction.
            - ADMIN ORG chỉ nhập nhóm trong scope; tạo tổ chức yêu cầu quyền quản trị toàn hệ thống.
            - Có lỗi thì không ghi dòng nào; errors.field dùng Sheet[dòng Excel].tênCột, trả tối đa 200 lỗi.
            """
    )
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    public BaseResponse<OrganizationImportResult> importFile(@RequestPart MultipartFile file,
        @RequestParam(defaultValue = "true") boolean dryRun) throws IOException {
        return BaseResponse.ok(imports.importFile(file, dryRun));
    }
}
