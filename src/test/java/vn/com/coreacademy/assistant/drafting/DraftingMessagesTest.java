package vn.com.coreacademy.assistant.drafting;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.com.coreacademy.assistant.conversation.ConversationService;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** One message per ready step, to the conversation the session came from: what was filled and what to check. */
class DraftingMessagesTest {
    private final ConversationService conversations = mock(ConversationService.class);
    private final DraftingMessages messages = new DraftingMessages(conversations);

    private static SessionRow session(Long conversationId) {
        return new SessionRow(5, "000005", 1, 9L, conversationId, null, null, 1, "active", null, "{}", null, null,
            null);
    }

    private String sent(int step) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(conversations).recordDraftingStep(eq(31L), text.capture(), eq(5L), eq(step));
        return text.getValue();
    }

    @Test
    void stepThreeNamesWhatItReallyProposes() {
        messages.proposed(session(31L), 3, List.of());

        assertThat(sent(3)).startsWith("Bước 3 · Bài kiểm tra & điều kiện:").contains("câu hỏi nháp")
            .contains("điểm thưởng").contains("năng lực").doesNotContain("điều kiện hoàn thành");
    }

    @Test
    void aTakenCodeIsToldToTheConversationWithTheNewCodeAndAStepOneCard() {
        messages.codeTaken(session(31L), "KH-01", "AI-NEW0001");

        assertThat(sent(1)).startsWith("Bước 1 · Thông tin cơ bản:").contains("KH-01").contains("AI-NEW0001")
            .endsWith(DraftingMessages.CHECK_AND_CONFIRM);
    }

    @Test
    void aTakenCodeOfASessionStartedFromThePageSendsNothing() {
        messages.codeTaken(session(null), "KH-01", "AI-NEW0001");

        verifyNoInteractions(conversations);
    }

    @Test
    void aReadyStepSaysWhatWasFilledWhatToCheckAndToConfirm() {
        messages.proposed(session(31L), 1, List.of("Chưa chọn được chương trình đào tạo phù hợp; hãy chọn trong "
            + "danh sách.", " "));

        String text = sent(1);
        assertThat(text).startsWith("Bước 1 · Thông tin cơ bản:").contains("mã khóa học")
            .contains("\n- Chưa chọn được chương trình đào tạo phù hợp").endsWith(DraftingMessages.CHECK_AND_CONFIRM);
        assertThat(text.lines().filter(line -> line.startsWith("- "))).hasSize(1);
    }

    @Test
    void stepFourCarriesThePublishBlockersFromItsNotes() {
        messages.proposed(session(31L), 4, List.of("Đề xuất giao cho: Đơn vị.",
            "Trước khi xuất bản còn: 3 bài chưa có nội dung.", CourseDraftingProposer.NOTHING_ASSIGNED));

        assertThat(sent(4)).startsWith("Bước 4 · Đối tượng & xuất bản:")
            .contains("- Trước khi xuất bản còn: 3 bài chưa có nội dung.")
            .contains(CourseDraftingProposer.NOTHING_ASSIGNED);
    }

    @Test
    void aFailedStepSaysWhyAndPointsAtRetryOrManual() {
        messages.failed(session(31L), 1, "Không đọc được tài liệu nguồn.");

        assertThat(sent(1)).contains("chưa soạn được").contains("Không đọc được tài liệu nguồn.")
            .contains("Thử lại").contains("Tự điền");
    }

    @Test
    void aSessionStartedFromThePageAloneGetsNoMessage() {
        messages.proposed(session(null), 2, List.of());

        verifyNoInteractions(conversations);
    }

    @Test
    void aMessageThatCannotBeStoredNeverFailsTheWorker() {
        when(conversations.recordDraftingStep(anyLong(), anyString(), anyLong(), anyInt()))
            .thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> messages.proposed(session(31L), 2, List.of())).doesNotThrowAnyException();
    }
}
