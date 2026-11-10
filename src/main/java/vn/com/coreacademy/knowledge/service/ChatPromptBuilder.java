package vn.com.coreacademy.knowledge.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.conversation.Conversations;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Message;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

/** Builds the grounded prompt: the model sees the numbered chunks and the question, nothing else. */
@Component
public class ChatPromptBuilder {
    private final String system;

    @Autowired
    public ChatPromptBuilder(SkillCatalog catalog) {
        this.system = SYSTEM + "\n" + catalog.base();
    }

    /** For callers without a Spring context; reads the same prompt files. */
    public ChatPromptBuilder() {
        this(new SkillCatalog());
    }

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
        return build(question, hits, List.of());
    }

    /**
     * Same, with the conversation's earlier exchanges ahead of the new question as plain turns, so a follow-up such
     * as "còn trường hợp ngoại lệ?" is read in context. Only the new question gets chunks.
     */
    public List<Message> build(String question, List<ChunkHit> hits, List<Conversations.Exchange> history) {
        StringBuilder user = new StringBuilder("NGỮ CẢNH:\n");
        for (int i = 0; i < hits.size(); i++) {
            user.append(formatChunk(i + 1, hits.get(i))).append("\n\n");
        }
        user.append("CÂU HỎI: ").append(question);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(system));
        for (Conversations.Exchange exchange : history) {
            messages.add(Message.user(exchange.question()));
            messages.add(Message.assistant(exchange.answer()));
        }
        messages.add(Message.user(user.toString()));

        return messages;
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
