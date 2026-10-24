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
        Nếu NGỮ CẢNH không có câu trả lời, chỉ trả lời đúng một câu, không kèm số đoạn nguồn:
        "%s"
        Nếu có câu trả lời, sau mỗi ý ghi số đoạn nguồn trong ngoặc vuông, ví dụ [1] hoặc [2][3].
        Trả lời bằng tiếng Việt, ngắn gọn, đúng thuật ngữ trong tài liệu.
        """.formatted(NotFoundAnswer.TEXT);

    /** {@code hits} are numbered [1]…[n] in the order given, and the same numbers name the citations. */
    public List<Message> build(String question, List<ChunkHit> hits) {
        StringBuilder user = new StringBuilder("NGỮ CẢNH:\n");
        for (int i = 0; i < hits.size(); i++) {
            user.append(formatChunk(i + 1, hits.get(i))).append("\n\n");
        }
        user.append("CÂU HỎI: ").append(question);

        return List.of(Message.system(SYSTEM), Message.user(user.toString()));
    }

    /** One numbered chunk as the model sees it: {@code [n] (file, trang p, dòng a–b)} then the text. */
    public static String formatChunk(int n, ChunkHit hit) {
        StringBuilder chunk = new StringBuilder();
        chunk.append('[').append(n).append("] (").append(hit.fileName());
        if (hit.page() != null) {
            chunk.append(", trang ").append(hit.page());
        }
        chunk.append(", dòng ").append(hit.lineStart()).append('–').append(hit.lineEnd()).append(")\n")
            .append(hit.content());

        return chunk.toString();
    }
}
