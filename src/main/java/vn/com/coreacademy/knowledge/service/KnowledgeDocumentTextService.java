package vn.com.coreacademy.knowledge.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.knowledge.dto.KnowledgeViews.DocumentTextView;
import vn.com.coreacademy.knowledge.dto.KnowledgeViews.TextChunkView;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.TextWindow;
import vn.com.coreacademy.security.SecurityContextUtils;

/**
 * Reads the extracted text of a document around a cited chunk for the source viewer. The visibility rule is the one
 * of search, applied in the same statement, so a document the caller cannot search answers not-found. Only the
 * extracted text is served, never the original file.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentTextService {
    private final DocumentChunkRepository chunks;
    private final KnowledgeAccess access;

    /** {@code aroundChunkId} null starts at the first chunk of the document. */
    @Transactional(readOnly = true)
    public DocumentTextView text(long documentId, Long aroundChunkId, int span) {
        String userId = SecurityContextUtils.getUserId();
        TextWindow window = chunks.window(documentId, aroundChunkId, span, access.visibility(userId))
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));

        return new DocumentTextView(window.documentId(), window.fileName(), window.aroundChunkId(),
            window.chunks().stream().map(chunk -> new TextChunkView(chunk.chunkId(), chunk.chunkIndex(),
                chunk.sectionPath(), chunk.page(), chunk.lineStart(), chunk.lineEnd(), chunk.text())).toList(),
            window.hasMoreBefore(), window.hasMoreAfter());
    }
}
