package vn.com.coreacademy.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.com.coreacademy.common.constants.HttpHeaderNames;

/**
 * Chạy trước Spring Security để lỗi xác thực có cùng requestId trong header, envelope và log.
 * Giữ ID qua async/error dispatch bằng request attribute; không tự truyền MDC sang task async.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestIdFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            String supplied = request.getHeader(HttpHeaderNames.REQUEST_ID);
            id = supplied != null && SAFE_ID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }

        String previous = MDC.get(RequestIdContext.MDC_KEY);
        MDC.put(RequestIdContext.MDC_KEY, id);
        response.setHeader(HttpHeaderNames.REQUEST_ID, id);

        long startedAt = System.nanoTime();
        log.info("HTTP request started method={} path={}", request.getMethod(), request.getRequestURI());
        try {
            chain.doFilter(request, response);
        } finally {
            log.info("HTTP request completed method={} path={} httpStatus={} durationMs={}", request.getMethod(),
                request.getRequestURI(), response.getStatus(), (System.nanoTime() - startedAt) / 1_000_000);
            // Thread của servlet được tái sử dụng; khôi phục MDC để không lẫn requestId giữa các yêu cầu.
            if (previous == null) {
                MDC.remove(RequestIdContext.MDC_KEY);
            } else {
                MDC.put(RequestIdContext.MDC_KEY, previous);
            }
        }
    }
}
