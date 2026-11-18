package vn.com.coreacademy.assistant.drafting;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.conversation.ConversationService;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;

/**
 * The chat side of a drafting session: when a step is ready (or could not be proposed), one assistant message goes to
 * the conversation the session was started from, saying what was filled and what the author must check, with the
 * step's "Đã chốt" card. A session started from the page alone has no conversation and gets none. Writing the message
 * never fails the worker: the page shows the step either way. Log lines carry ids only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftingMessages {
    /** The editor's names of the four steps, step 1 first. */
    static final List<String> STEP_NAMES = List.of("Thông tin cơ bản", "Nội dung khóa học",
        "Bài kiểm tra & điều kiện", "Đối tượng & xuất bản");
    private static final List<String> FILLED = List.of(
        "tên, mô tả ngắn, mục tiêu đầu ra, mức độ, loại khóa học, chương trình, phân hệ và mã khóa học",
        "dàn ý gồm chương, bài và một dòng mô tả mỗi bài; nội dung bài để trống cho bạn",
        "điều kiện hoàn thành, điểm thưởng và các câu hỏi nháp từ tài liệu",
        "đối tượng được giao và những gì còn thiếu trước khi xuất bản");
    static final String CHECK_AND_CONFIRM = "Bạn xem lại trên trang, sửa nếu cần rồi bấm \"Đã chốt\".";
    static final String RETRY_OR_MANUAL = "Bạn mở trang soạn để bấm \"Thử lại\" hoặc \"Tự điền\".";

    private final ConversationService conversations;

    /** Step {@code step} holds a new proposal; {@code notes} are the proposer's lines for the author. */
    public void proposed(SessionRow session, int step, List<String> notes) {
        StringBuilder text = new StringBuilder(heading(step)).append(" mình đã điền ")
            .append(FILLED.get(step - 1)).append('.');
        if (notes != null) {
            notes.stream().filter(note -> note != null && !note.isBlank())
                .forEach(note -> text.append("\n- ").append(note.strip()));
        }
        text.append("\n\n").append(CHECK_AND_CONFIRM);
        send(session, step, text.toString());
    }

    /** Step {@code step} could not be proposed; {@code error} is the message the page shows too. */
    public void failed(SessionRow session, int step, String error) {
        String text = heading(step) + " mình chưa soạn được bước này."
            + (error == null || error.isBlank() ? "" : " " + error.strip()) + "\n\n" + RETRY_OR_MANUAL;
        send(session, step, text);
    }

    private static String heading(int step) {
        return "Bước " + step + " · " + STEP_NAMES.get(step - 1) + ":";
    }

    private void send(SessionRow session, int step, String text) {
        if (session.conversationId() == null) {
            return;
        }
        try {
            conversations.recordDraftingStep(session.conversationId(), text, session.id(), step);
        } catch (RuntimeException exception) {
            log.warn("Drafting step message not stored sessionId={} step={} type={}", session.id(), step,
                exception.getClass().getName());
        }
    }
}
