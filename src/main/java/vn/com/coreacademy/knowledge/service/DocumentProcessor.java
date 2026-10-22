package vn.com.coreacademy.knowledge.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.chunking.Chunk;
import vn.com.coreacademy.knowledge.chunking.Chunker;
import vn.com.coreacademy.knowledge.entity.KnowledgeDocument;
import vn.com.coreacademy.knowledge.extraction.DocxTextExtractor;
import vn.com.coreacademy.knowledge.extraction.ExtractedText;
import vn.com.coreacademy.knowledge.extraction.ExtractionException;
import vn.com.coreacademy.knowledge.extraction.KnowledgeFileType;
import vn.com.coreacademy.knowledge.extraction.MarkdownTextExtractor;
import vn.com.coreacademy.knowledge.extraction.PdfTextExtractor;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.NewChunk;

/** extract → chunk → embed for one document. No database access, so it runs outside any transaction. */
@Component
@RequiredArgsConstructor
public class DocumentProcessor {
    private final KnowledgeFileStore store;
    private final PdfTextExtractor pdf;
    private final MarkdownTextExtractor markdown;
    private final DocxTextExtractor docx;
    private final Chunker chunker;
    private final OllamaClient ollama;

    public record Processed(List<NewChunk> chunks, Integer pageCount) {
    }

    public Processed process(KnowledgeDocument document) {
        Path file = store.resolve(document.getStorageKey());
        ExtractedText text = switch (KnowledgeFileType.fromMimeType(document.getMimeType())) {
            case PDF -> pdf.extract(file);
            case MARKDOWN -> markdown.extract(file);
            case DOCX -> docx.extract(file);
        };

        List<Chunk> chunks = chunker.chunk(text.lines());
        if (chunks.isEmpty()) {
            throw ExtractionException.noText();
        }

        List<float[]> vectors = ollama.embed(chunks.stream().map(Chunk::content).toList());
        List<NewChunk> stored = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            stored.add(new NewChunk(i, chunk.content(), chunk.page(), chunk.lineStart(), chunk.lineEnd(),
                chunk.tokenEstimate(), vectors.get(i)));
        }

        return new Processed(stored, text.pageCount());
    }
}
