package vn.com.coreacademy.knowledge.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

public final class ChatViews {
    private ChatViews() {
    }

    @Schema(description = "Một nguồn trích dẫn, dựng từ metadata của đoạn đã tìm thấy, không từ văn bản model.")
    public record CitationView(
        @Schema(description = "Số thứ tự [n] của đoạn trong câu trả lời.") int n,
        long documentId,
        String fileName,
        @Schema(description = "Số trang bắt đầu từ 1; null với .md và .docx.") Integer page,
        @Schema(description = "Dòng đầu (trong trang với PDF; đoạn với DOCX).") int lineStart,
        int lineEnd,
        @Schema(description = "280 ký tự đầu của đoạn, cắt ở ranh giới từ.") String snippet,
        @Schema(description = "Chỉ có khi nguồn là bài học của khóa đã xuất bản; khi đó documentId là 0.")
        @JsonInclude(JsonInclude.Include.NON_NULL) LessonCitation lesson) {
        public CitationView(int n, long documentId, String fileName, Integer page, int lineStart, int lineEnd,
                            String snippet) {
            this(n, documentId, fileName, page, lineStart, lineEnd, snippet, null);
        }
    }

    @Schema(description = "Bài học được trích dẫn; url mở bài học trong ứng dụng.")
    public record LessonCitation(long courseId, String courseTitle, long revisionId, String chapterTitle,
                                 long lessonId, String lessonTitle, long blockId, String url) {
    }

    @Schema(description = "Câu trả lời; citations là các đoạn được ghi [n] trong answer; found=false thì rỗng.")
    public record ChatAnswerView(String answer, boolean found, List<CitationView> citations) {
    }
}
