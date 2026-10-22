package vn.com.coreacademy.knowledge.extraction;

import lombok.Getter;

/** A document that cannot be read; the message is Vietnamese and is shown to the user as the failure reason. */
@Getter
public class ExtractionException extends RuntimeException {
    public ExtractionException(String message) {
        super(message);
    }

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }

    public static ExtractionException unreadablePdf(Throwable cause) {
        return new ExtractionException("Không đọc được nội dung PDF. Tệp có thể bị hỏng hoặc được đặt mật khẩu.",
            cause);
    }

    public static ExtractionException noTextLayer() {
        return new ExtractionException(
            "PDF không có lớp văn bản (có thể là bản scan). Hệ thống chưa hỗ trợ nhận dạng chữ.");
    }

    public static ExtractionException noText() {
        return new ExtractionException("Tài liệu không có nội dung văn bản.");
    }
}
