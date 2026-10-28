package vn.com.coreacademy.assessment.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Giờ server cho mốc thời gian được lưu hoặc trả về của lượt thi. PostgreSQL timestamptz chỉ giữ micro giây; cắt
 * trước khi lưu để response ngay sau khi ghi trùng khớp giá trị FE đọc lại về sau (so sánh, hiển thị, idempotent).
 */
final class ServerTime {
    private ServerTime() {
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
