package vn.com.coreacademy.assistant.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;
import vn.com.coreacademy.common.exception.ApiResponseCode;

@Getter
public enum AssistantResponseCode implements ApiResponseCode {
    AI_UNAVAILABLE("ASSISTANT-AI_UNAVAILABLE", "Dịch vụ AI tạm thời không phản hồi. Vui lòng thử lại sau.",
        "Dịch vụ AI chưa sẵn sàng"),
    // One code for a missing, expired, cancelled or foreign action, so an id reveals nothing about other users.
    ACTION_NOT_FOUND("ASSISTANT-ACTION_NOT_FOUND",
        "Yêu cầu này đã hết hạn hoặc đã được xử lý. Vui lòng yêu cầu lại trợ lý.", "Yêu cầu không còn hiệu lực"),
    // One user's second turn while the first runs, or the server's limit of turns reached.
    BUSY("ASSISTANT-BUSY", "Trợ lý đang bận trả lời. Bạn đợi câu trả lời trước xong rồi thử lại nhé.", "Trợ lý đang bận"),
    // A user over the hour's questions; streams and the stream-less chat carry the wait time in the message.
    RATE_LIMITED("ASSISTANT-RATE_LIMITED", "Bạn đã hỏi nhiều, vui lòng thử lại sau ít phút.",
        "Đã đạt giới hạn câu hỏi"),
    // One code for a missing or foreign conversation, so an id reveals nothing about other users.
    CONVERSATION_NOT_FOUND("ASSISTANT-CONVERSATION_NOT_FOUND", "Không tìm thấy hội thoại này.",
        "Không tìm thấy hội thoại"),
    // One code for a missing or foreign turn, so an id reveals nothing about other users.
    TURN_NOT_FOUND("ASSISTANT-TURN_NOT_FOUND", "Không tìm thấy câu trả lời này.", "Không tìm thấy câu trả lời"),
    // One code for a missing, unpublished or unreadable lesson, so an id reveals nothing about other courses.
    LESSON_NOT_FOUND("ASSISTANT-LESSON_NOT_FOUND", "Không tìm thấy bài học này hoặc bạn không có quyền xem.",
        "Không tìm thấy bài học"),
    // A user over the day's course generation jobs; the message carries the wait time.
    JOB_RATE_LIMITED("ASSISTANT-JOB_RATE_LIMITED", "Bạn đã tạo đủ số khóa học bằng AI cho phép trong ngày.",
        "Đã đạt giới hạn tạo khóa học"),
    ACTION_STALE("ASSISTANT-ACTION_STALE", "Dữ liệu đã thay đổi từ lúc trợ lý đề xuất. Vui lòng yêu cầu lại.",
        "Dữ liệu đã thay đổi"),
    // The old "Tạo bằng AI" job start, replaced by "Soạn cùng trợ lý" (ADR 0002). The endpoint is gone, not refusing a
    // business rule, so it answers HTTP 410 rather than 200 ERROR (spec decision; DEVELOPMENT_GUIDELINES 13.2 exception).
    COURSE_JOBS_RETIRED(HttpStatus.GONE, "ASSISTANT-COURSE_JOBS_RETIRED",
        "Tạo khóa học bằng AI theo cách cũ đã ngừng. Hãy dùng Soạn cùng trợ lý.", "Tính năng đã thay đổi");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
    private final String title;

    AssistantResponseCode(String code, String message, String title) {
        this(HttpStatus.OK, code, message, title);
    }

    AssistantResponseCode(HttpStatus httpStatus, String code, String message, String title) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.message = message;
        this.title = title;
    }
}
