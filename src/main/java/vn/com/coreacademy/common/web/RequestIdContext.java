package vn.com.coreacademy.common.web;

import org.slf4j.MDC;
import java.util.UUID;

/** No extra ThreadLocal: use the same context as logging. */
public final class RequestIdContext {
    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private RequestIdContext() {}
    public static String currentOrNew() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
