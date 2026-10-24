package vn.com.coreacademy.assistant.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import vn.com.coreacademy.common.exception.ApiResponseCode;

@Getter
@RequiredArgsConstructor
public enum AssistantResponseCode implements ApiResponseCode {
    AI_UNAVAILABLE("ASSISTANT-AI_UNAVAILABLE", "Dịch vụ AI tạm thời không phản hồi. Vui lòng thử lại sau.",
        "Dịch vụ AI chưa sẵn sàng"),
    // One code for a missing, expired, cancelled or foreign action, so an id reveals nothing about other users.
    ACTION_NOT_FOUND("ASSISTANT-ACTION_NOT_FOUND",
        "Yêu cầu này đã hết hạn hoặc đã được xử lý. Vui lòng yêu cầu lại trợ lý.", "Yêu cầu không còn hiệu lực"),
    // One user's second turn while the first runs, or the server's limit of turns reached.
    BUSY("ASSISTANT-BUSY", "Trợ lý đang bận trả lời. Bạn đợi câu trả lời trước xong rồi thử lại nhé.", "Trợ lý đang bận"),
    ACTION_STALE("ASSISTANT-ACTION_STALE", "Dữ liệu đã thay đổi từ lúc trợ lý đề xuất. Vui lòng yêu cầu lại.",
        "Dữ liệu đã thay đổi");

    private final HttpStatus httpStatus = HttpStatus.OK;
    private final String code;
    private final String message;
    private final String title;
}
