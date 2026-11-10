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
}
