package vn.com.coreacademy.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.exception.ResponseCode;

/**
 * Trả lỗi filter chain theo cùng envelope với MVC, giữ Bearer challenge cho 401.
 * Không lộ chi tiết exception; header cache do SecurityConfig quản lý.
 */
@Component
@RequiredArgsConstructor
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, SecurityConstants.TOKEN_TYPE);
        write(response, ResponseCode.UNAUTHORIZED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(response, ResponseCode.FORBIDDEN);
    }

    private void write(HttpServletResponse response, ResponseCode code) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        response.setStatus(code.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getOutputStream(), BaseResponse.fail(code));
    }
}
