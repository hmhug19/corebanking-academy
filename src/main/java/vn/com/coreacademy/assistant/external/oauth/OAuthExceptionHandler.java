package vn.com.coreacademy.assistant.external.oauth;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** OAuth endpoints answer their errors in the OAuth shape, not as a BaseResponse; other errors keep the global one. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = OAuthExceptionHandler.class)
public class OAuthExceptionHandler {
    @ExceptionHandler(OAuthException.class)
    ResponseEntity<Map<String, Object>> oauth(OAuthException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", exception.error());
        body.put("error_description", exception.getMessage());
        return ResponseEntity.status(exception.status()).cacheControl(CacheControl.noStore()).body(body);
    }
}
