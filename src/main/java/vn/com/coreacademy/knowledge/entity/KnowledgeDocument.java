package vn.com.coreacademy.knowledge.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.com.coreacademy.common.persistence.CreatedEntity;

/** Tài liệu trong kho dùng chung; bản ghi đồng thời là công việc của worker nền. Đoạn văn bản nằm ở bảng riêng. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "knowledge_documents", schema = "learning_hub")
public class KnowledgeDocument extends CreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "document_id", nullable = false)
    private Long id;

    @Column(name = "uploaded_by", length = 20, nullable = false)
    private String uploadedBy;

    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    /** What people see and what citations show; the owner may change it. Defaults to the file name. */
    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "mime_type", length = 100, nullable = false)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_key", length = 100, nullable = false)
    private String storageKey;

    @Column(name = "status", length = 20, nullable = false)
    @Convert(converter = DocumentStatusConverter.class)
    private DocumentStatus status = DocumentStatus.QUEUED;

    @Column(name = "scope", length = 20, nullable = false)
    @Convert(converter = DocumentScopeConverter.class)
    private DocumentScope scope = DocumentScope.PERSONAL;

    @Column(name = "scope_org_id")
    private Long scopeOrgId;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    /** SHA-256 (hex) of the current file; null for documents older than the column until the start-up fill. */
    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    /** A replacement file waits on the same row; the current file and chunks answer until the swap. */
    @Column(name = "replacement_status", length = 20)
    @Convert(converter = DocumentStatusConverter.class)
    private DocumentStatus replacementStatus;

    @Column(name = "replacement_error", length = 500)
    private String replacementError;

    @Column(name = "replacement_storage_key", length = 100)
    private String replacementStorageKey;

    @Column(name = "replacement_file_name", length = 255)
    private String replacementFileName;

    @Column(name = "replacement_mime_type", length = 100)
    private String replacementMimeType;

    @Column(name = "replacement_size_bytes")
    private Long replacementSizeBytes;

    @Column(name = "replacement_checksum", length = 64)
    private String replacementChecksum;

    @PrePersist
    void defaultTitle() {
        if (title == null || title.isBlank()) {
            title = fileName;
        }
    }
}
