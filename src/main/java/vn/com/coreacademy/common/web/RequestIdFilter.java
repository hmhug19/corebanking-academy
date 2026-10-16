package vn.com.coreacademy.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = (String) request.getAttribute(ATTRIBUTE);
        if (id == null) {
            String supplied = request.getHeader(RequestIdContext.HEADER);
            id = supplied != null && SAFE_ID.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
            request.setAttribute(ATTRIBUTE, id);
        }
        String previous = MDC.get(RequestIdContext.MDC_KEY);
        MDC.put(RequestIdContext.MDC_KEY, id);
        response.setHeader(RequestIdContext.HEADER, id);
        try { chain.doFilter(request, response); }
        finally {
            if (previous == null) MDC.remove(RequestIdContext.MDC_KEY);
            else MDC.put(RequestIdContext.MDC_KEY, previous);
        }
    }
}
