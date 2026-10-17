package vn.com.coreacademy.common.exception;

import lombok.Getter;

/** Đánh dấu field ngoài whitelist của PATCH; không giữ giá trị gửi lên. */
@Getter
public class UnsupportedFieldException extends IllegalArgumentException {
    private final String field;

    public UnsupportedFieldException(String field) {
        super("Unsupported request field");
        this.field = field;
    }
}
