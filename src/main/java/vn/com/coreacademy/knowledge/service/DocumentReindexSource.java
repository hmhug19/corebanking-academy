package vn.com.coreacademy.knowledge.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.KnowledgeDocumentRepository;

/**
 * Ready documents with chunks of another chunker version or embed model. Each is read from its stored file, cut and
 * embedded outside any transaction, and then its chunks are swapped in one short transaction.
 */
@Component
@RequiredArgsConstructor
public class DocumentReindexSource implements ReindexSource {
    private final DocumentChunkRepository chunks;
    private final KnowledgeDocumentRepository documents;
    private final DocumentProcessor processor;
    private final TransactionTemplate transaction;

    @Override
    public String kind() {
        return "document";
    }

    @Override
    public List<Item> stale() {
        return chunks.staleDocuments().stream().map(id -> new Item(id, () -> reindex(id))).toList();
    }

    private void reindex(long documentId) {
        documents.findById(documentId).ifPresent(document -> {
            DocumentProcessor.Processed result = processor.process(document);
            transaction.executeWithoutResult(status ->
                chunks.replaceIfReady(documentId, document.getStorageKey(), result.chunks()));
        });
    }
}
