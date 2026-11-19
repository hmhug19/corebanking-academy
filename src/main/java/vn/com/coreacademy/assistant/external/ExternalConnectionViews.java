package vn.com.coreacademy.assistant.external;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** What the Kết nối trợ lý ngoài endpoints receive and send. */
public final class ExternalConnectionViews {
    private ExternalConnectionViews() {
    }

    @Schema(description = "Tạo kết nối: tên để nhận ra và số ngày hiệu lực (30 hoặc 90).")
    public record CreateConnection(
        @NotBlank @Size(max = ExternalConnectionCodes.MAX_NAME) String name,
        @NotNull @Schema(description = "30 hoặc 90") Integer validDays) {
    }

    @Schema(description = "Một kết nối còn hiệu lực hoặc đã hết hạn (chưa thu hồi); không bao giờ có token.")
    public record ConnectionView(long id,
                                 @Schema(description = "Tên người dùng đặt (token) hoặc tên client tự khai (oauth)")
                                 String name,
                                 @Schema(description = "token: mã chan_ext_ dán tay; oauth: cấp qua trang Cho phép kết nối")
                                 String kind,
                                 @Schema(description = "Vài ký tự đầu của mã chan_ext_; null với kết nối oauth",
                                     nullable = true) String tokenPrefix,
                                 OffsetDateTime createTime, OffsetDateTime expiresAt, OffsetDateTime lastUsedAt) {
    }

    @Schema(description = "Kết nối vừa tạo và token của nó; token chỉ trả về đúng một lần, ở đây.")
    public record CreatedConnection(ConnectionView connection, String token) {
        @Override
        public String toString() {
            return "CreatedConnection[connection=" + connection + ", token=***]";
        }
    }

    @Schema(description = """
        Tính năng Trợ lý ngoài: enabled là cờ cấu hình; allowed là người gọi được tạo kết nối (vai trò quản lý khóa học \
        có chức năng AI_ASSISTANT, và cờ đang bật); mcpUrl là địa chỉ MCP để dán vào Claude App / Claude Code.
        """)
    public record ExternalAssistantStatus(boolean enabled, boolean allowed,
                                          @Schema(description = "{app-base-url}/api/v1/mcp, đúng resource mà OAuth chấp nhận")
                                          String mcpUrl) {
    }
}
