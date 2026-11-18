package vn.com.coreacademy.assistant.external;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assistant.external.ExternalConnectionViews.ConnectionView;
import vn.com.coreacademy.assistant.external.ExternalConnectionViews.CreateConnection;
import vn.com.coreacademy.assistant.external.ExternalConnectionViews.CreatedConnection;
import vn.com.coreacademy.assistant.external.ExternalConnectionViews.ExternalAssistantStatus;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@RequestMapping(value = "/api/v1/assistant/external-connections", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Assistant external connections")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class ExternalConnectionController {
    private static final String WHO = """
        - Chỉ vai trò quản lý khóa học (INSTRUCTOR, ADMIN, SUPER_ADMIN) có chức năng AI_ASSISTANT; người khác nhận 403.
        - Cờ app.assistant.external.enabled tắt (mặc định): trả HTTP 404 CORE-ENDPOINT_NOT_FOUND.
        """;

    private final ExternalConnectionService connections;

    @GetMapping("/status")
    @Operation(
        summary = "Tính năng Trợ lý ngoài có bật và người gọi có tạo được kết nối không",
        description = """
            Luôn trả về, kể cả khi cờ tắt, để trang ẩn hoặc hiện nút Kết nối trợ lý ngoài.
            - enabled: cờ app.assistant.external.enabled.
            - allowed: cờ bật và người gọi là vai trò quản lý khóa học có chức năng AI_ASSISTANT.
            """
    )
    public BaseResponse<ExternalAssistantStatus> status() {
        return BaseResponse.ok(connections.status());
    }

    @GetMapping
    @Operation(summary = "Các kết nối trợ lý ngoài của người gọi",
        description = "Kết nối chưa thu hồi (kể cả đã hết hạn), mới nhất trước; không bao giờ có token.\n" + WHO)
    public BaseResponse<List<ConnectionView>> list() {
        return BaseResponse.ok(connections.list());
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Tạo kết nối trợ lý ngoài",
        description = """
            Trả token dạng chan_ext_… đúng một lần; server chỉ lưu SHA-256 và vài ký tự đầu của token.
            - name: 1–100 ký tự; validDays: 30 hoặc 90, khác đi trả CORE-VALIDATION_FAILED trường validDays.
            - Token chỉ dùng được trên /api/v1/mcp; mọi API khác từ chối nó (401).
            """ + WHO
    )
    public BaseResponse<CreatedConnection> create(@Valid @RequestBody CreateConnection request) {
        return BaseResponse.ok(connections.create(request));
    }

    @DeleteMapping("/{connectionId}")
    @Operation(summary = "Thu hồi kết nối trợ lý ngoài",
        description = """
            Kết nối ngừng hoạt động ngay (trợ lý ngoài nhận 401) và không còn trong danh sách.
            - Kết nối của người khác hoặc đã thu hồi trả CORE-RESOURCE_NOT_FOUND.
            """ + WHO)
    public BaseResponse<Void> revoke(@PathVariable @Positive Long connectionId) {
        connections.revoke(connectionId);
        return BaseResponse.ok(null);
    }
}
