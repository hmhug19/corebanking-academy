package vn.com.coreacademy.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true", matchIfMissing = true)
public class OpenApiConfig {
    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    OpenAPI academyOpenApi() {
        return new OpenAPI()
            .info(new Info().title("CORE Academy API").version("v1")
                .description("API hệ thống đào tạo nội bộ, quản lý tài khoản, tổ chức và phân quyền. "
                    + "Đăng nhập bằng tên đăng nhập hoặc mã cán bộ, sau đó nhập accessToken vào Authorize "
                    + "để gọi API cần xác thực. Request nhận trực tiếp dữ liệu theo schema, không bọc trong data. "
                    + "Response có dạng BaseResponse: luôn kiểm tra status=SUCCESS hoặc ERROR, kể cả HTTP 200. "
                    + "Validation/lỗi nghiệp vụ trả HTTP 200; lỗi xác thực 401, phân quyền 403, hệ thống 500. "
                    + "Lỗi giao thức giữ HTTP tương ứng. messageCode có dạng PREFIX-REASON, không chứa HTTP status; "
                    + "message/messageTitle bằng tiếng Việt, errors chứa chi tiết theo field. "
                    + "Dùng responseId khi cần hỗ trợ tra cứu."))
            .components(new Components().addSecuritySchemes(BEARER_AUTH,
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                    .bearerFormat("JWT")
                    .description("Nhập accessToken nhận được khi đăng nhập; không nhập thêm tiền tố Bearer.")))
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
