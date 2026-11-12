package vn.com.coreacademy.assistant.conversation;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assistant.conversation.ConversationViews.SharedFeedbackView;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.export.CsvWriter;
import vn.com.coreacademy.common.web.PageQuery;

/**
 * What a reviewer may read: the feedback rows whose owner chose to share the turn, newest first. It reads only the copy
 * stored on the feedback row, never the conversation or its other turns, and returns no owner or conversation id.
 */
@Service
@RequiredArgsConstructor
public class FeedbackReviewService {
    static final int EXPORT_LIMIT = 10_000;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "sharedTime")
        .and(Sort.by(Sort.Direction.DESC, "turnId"));

    private final FeedbackRepository feedback;
    private final ObjectMapper json;

    @Transactional(readOnly = true)
    public PageResponse<SharedFeedbackView> list(String reason, String skill, int page, int size) {
        PageRequest checked = PageQuery.of(page, size, "sharedTime,desc", Set.of("sharedTime"));
        PageRequest paging = PageRequest.of(checked.getPageNumber(), checked.getPageSize(), NEWEST_FIRST);
        Page<AssistantFeedback> result = feedback.findShared(blank(reason), blank(skill), paging);

        return PageResponse.from(result.map(this::view));
    }

    @Transactional(readOnly = true)
    public byte[] export(String reason, String skill) {
        List<Object[]> rows = feedback.findShared(blank(reason), blank(skill),
                PageRequest.of(0, EXPORT_LIMIT, NEWEST_FIRST)).stream()
            .map(row -> new Object[] {row.getSharedTime(), row.getReasonCode(), row.getNote(), row.getSharedSkill(),
                row.getSharedPromptVersion(), row.getSharedQuestion(), row.getSharedAnswer(),
                row.getSharedCitations(), row.getSharedRetrievalTrace()})
            .toList();

        return CsvWriter.write(List.of("sharedTime", "reasonCode", "note", "skill", "promptVersion", "question",
            "answer", "citations", "retrievalTrace"), rows);
    }

    private SharedFeedbackView view(AssistantFeedback row) {
        return new SharedFeedbackView(row.getTurnId(), row.getSharedTime(), row.getReasonCode(), row.getNote(),
            row.getSharedSkill(), row.getSharedPromptVersion(), row.getSharedQuestion(), row.getSharedAnswer(),
            node(row.getSharedCitations()), node(row.getSharedRetrievalTrace()));
    }

    private JsonNode node(String text) {
        try {
            return text == null ? json.createArrayNode() : json.readTree(text);
        } catch (JsonProcessingException e) {
            return json.createArrayNode();
        }
    }

    /** An empty filter is passed as "" so the query needs no typed null parameter. */
    private static String blank(String value) {
        return value == null ? "" : value.strip();
    }
}
