package vn.com.coreacademy.assistant.conversation;

import java.time.Instant;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assistant.conversation.ConversationViews.FeedbackView;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.assistant.metrics.AssistantMetrics;
import vn.com.coreacademy.common.exception.BusinessException;

/** Stores the owner's thumbs on one turn. A turn that is not the caller's answers like one that does not exist. */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {
    static final Set<String> REASONS = Set.of("irrelevant", "incorrect", "outdated", "missing_source", "other");
    static final int NOTE_CHARS = 2000;

    private final TurnRepository turns;
    private final ConversationRepository conversations;
    private final FeedbackRepository feedback;
    private final AssistantMetrics metrics;

    @Transactional
    public FeedbackView put(String ownerId, long turnId, boolean helpful, String reasonCode, String note,
                            boolean share) {
        String reason = reasonCode == null || reasonCode.isBlank() ? null : reasonCode.strip();
        if (reason != null && (helpful || !REASONS.contains(reason))) {
            throw BusinessException.validation("reasonCode", "INVALID",
                helpful ? "Chỉ đánh giá chưa tốt mới có lý do." : "Lý do không hợp lệ.");
        }
        if (share && helpful) {
            throw BusinessException.validation("share", "INVALID", "Chỉ đánh giá chưa tốt mới gửi kèm được.");
        }
        String text = note == null || note.isBlank() ? null : note.strip();
        if (text != null && text.length() > NOTE_CHARS) {
            throw BusinessException.validation("note", "OUT_OF_RANGE", "Ghi chú tối đa " + NOTE_CHARS + " ký tự.");
        }
        AssistantTurn turn = turns.findById(turnId).orElseThrow(FeedbackService::notFound);
        if (!conversations.existsByIdAndOwnerId(turn.getConversationId(), ownerId)) {
            throw notFound();
        }
        AssistantFeedback row = feedback.findById(turnId).orElseGet(AssistantFeedback::new);
        row.setTurnId(turnId);
        row.setOwnerId(ownerId);
        row.setHelpful(helpful);
        row.setReasonCode(reason);
        row.setNote(text);
        if (share) {
            row.share(turn, Instant.now());
        } else {
            row.unshare();
        }
        feedback.save(row);
        metrics.feedback(AssistantMetrics.surfaceOf(turn.getSurface()), helpful, reason);
        // Ids and the thumb only: the note is the user's text and never reaches a log.
        log.info("Assistant feedback turnId={} helpful={} reason={}", turnId, helpful, reason);

        return new FeedbackView(helpful, reason, text, share);
    }

    private static BusinessException notFound() {
        return new BusinessException(AssistantResponseCode.TURN_NOT_FOUND);
    }
}
