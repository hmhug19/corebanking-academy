package vn.com.coreacademy.knowledge.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import vn.com.coreacademy.common.exception.ApiResponseCode;

@Getter
@RequiredArgsConstructor
public enum KnowledgeResponseCode implements ApiResponseCode {
    FILE_TYPE_UNSUPPORTED("KNOWLEDGE-FILE_TYPE_UNSUPPORTED", "Chỉ hỗ trợ tệp .pdf, .md hoặc .docx.",
        "Loại tệp không hỗ trợ"),
    FILE_INVALID("KNOWLEDGE-FILE_INVALID", "Tệp rỗng, bị hỏng hoặc nội dung không khớp với loại tệp.",
        "Tệp không hợp lệ"),
    // The limit comes from app.knowledge.max-file-size; the message stays generic so it cannot disagree with it.
    FILE_TOO_LARGE("KNOWLEDGE-FILE_TOO_LARGE", "Tệp vượt quá dung lượng tối đa cho phép.", "Tệp quá lớn"),
    AI_UNAVAILABLE("KNOWLEDGE-AI_UNAVAILABLE", "Dịch vụ AI tạm thời không phản hồi. Vui lòng thử lại sau.",
        "Dịch vụ AI chưa sẵn sàng");

    private final HttpStatus httpStatus = HttpStatus.OK;
    private final String code;
    private final String message;
    private final String title;
}
