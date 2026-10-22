package vn.com.coreacademy.knowledge.service;

import java.util.List;

import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Message;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

/** Builds the grounded prompt: the model sees the numbered chunks and the question, nothing else. */
@Component
public class ChatPromptBuilder {
    private static final String SYSTEM = """
        Bạn là trợ lý tra cứu tài liệu nội bộ của Ban QL&PT Core Banking.
        Chỉ dùng các đoạn trong phần NGỮ CẢNH dưới đây để trả lời. Không dùng kiến thức bên ngoài.
        Nếu NGỮ CẢNH không có câu trả lời, chỉ trả lời đúng một câu:
        "%s"
        Sau mỗi ý, ghi số đoạn nguồn trong ngoặc vuông, ví dụ [1] hoặc [2][3].
        Trả lời bằng tiếng Việt, ngắn gọn, đúng thuật ngữ trong tài liệu.
        """.formatted(NotFoundAnswer.TEXT);

    /** {@code hits} are numbered [1]…[n] in the order given, and the same numbers name the citations. */
    public List<Message> build(String question, List<ChunkHit> hits) {
        StringBuilder user = new StringBuilder("NGỮ CẢNH:\n");
        for (int i = 0; i < hits.size(); i++) {
            ChunkHit hit = hits.get(i);
            user.append('[').append(i + 1).append("] (").append(hit.fileName());
            if (hit.page() != null) {
                user.append(", trang ").append(hit.page());
            }
            user.append(", dòng ").append(hit.lineStart()).append('–').append(hit.lineEnd()).append(")\n")
                .append(hit.content()).append("\n\n");
        }
        user.append("CÂU HỎI: ").append(question);

        return List.of(Message.system(SYSTEM), Message.user(user.toString()));
    }
}
