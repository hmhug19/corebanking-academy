package vn.com.coreacademy.knowledge.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

public final class KnowledgeViews {
    private KnowledgeViews() {
    }

    public record UploaderView(String userId, String fullName) {
    }

    @Schema(description = "Tài liệu trong kho dùng chung; status là queued, processing, ready hoặc failed.")
    public record KnowledgeDocumentView(
        Long id,
        String fileName,
        String mimeType,
        long sizeBytes,
        @Schema(description = "personal (chỉ người tải lên), unit (đơn vị và đơn vị con) hoặc global (mọi người).")
        String scope,
        @Schema(description = "Đơn vị của tài liệu khi scope = unit, ngược lại null.") Long scopeOrgId,
        String status,
        @Schema(description = "Lý do tiếng Việt khi status = failed, ngược lại null.") String errorMessage,
        @Schema(description = "Số trang; chỉ có với PDF đã xử lý.") Integer pageCount,
        int chunkCount,
        UploaderView uploadedBy,
        Instant createdAt,
        @Schema(description = "Thời điểm xử lý xong (ready hoặc failed); null khi chưa xong.") Instant processedAt) {
    }

    @Schema(description = "Văn bản đã trích của một đoạn được trích dẫn và các đoạn kề, theo thứ tự đọc. Không có tệp gốc.")
    public record DocumentTextView(
        long documentId,
        String fileName,
        @Schema(description = "Đoạn được trích dẫn (đoạn đầu tiên của tài liệu khi không truyền around).")
        long aroundChunkId,
        java.util.List<TextChunkView> chunks,
        @Schema(description = "Còn đoạn trước đoạn đầu của danh sách.") boolean hasMoreBefore,
        @Schema(description = "Còn đoạn sau đoạn cuối của danh sách.") boolean hasMoreAfter) {
    }

    public record TextChunkView(
        long chunkId,
        int chunkIndex,
        @Schema(description = "Đường dẫn mục (các tiêu đề nối bằng ›); rỗng nếu đoạn nằm trước tiêu đề đầu.")
        String sectionPath,
        @Schema(description = "Số trang bắt đầu từ 1; null với .md và .docx.") Integer page,
        int lineStart,
        int lineEnd,
        String text) {
    }
}
