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
                        .description("API đào tạo nội bộ. Đăng nhập bằng username hoặc mã cán bộ, "
                                + "sau đó dán accessToken vào Authorize để gọi API cần xác thực."))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .bearerFormat("JWT").description("Chỉ dán accessToken, không thêm tiền tố Bearer.")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
