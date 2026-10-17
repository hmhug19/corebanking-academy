package vn.com.coreacademy.common.web;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Dùng chung MDC với logging để envelope và log có cùng requestId, không tạo ThreadLocal riêng.
 */
public final class RequestIdContext {
    public static final String MDC_KEY = "requestId";

    private RequestIdContext() {
    }

    /**
     * Dùng ID từ MDC hoặc tạo ID dự phòng khi chạy ngoài filter; không ghi ID mới vào MDC.
     */
    public static String currentOrNew() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
