package vn.com.coreacademy.learning.index;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.extraction.DocxTextExtractor;
import vn.com.coreacademy.knowledge.extraction.ExtractedText;
import vn.com.coreacademy.knowledge.extraction.ExtractionException;
import vn.com.coreacademy.knowledge.extraction.KnowledgeFileType;
import vn.com.coreacademy.knowledge.extraction.MarkdownTextExtractor;
import vn.com.coreacademy.knowledge.extraction.PdfTextExtractor;
import vn.com.coreacademy.knowledge.extraction.TextLine;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.BlockAsset;
import vn.com.coreacademy.storage.service.ObjectStorage;

/**
 * Reads the words of the files attached to pdf and attachment blocks with the knowledge extractors, so their limits
 * and readability check apply. A file that cannot be read as text (a scan, a legacy font, too long, a format the
 * extractors do not know) gives no lines: the block stays unindexed and the revision still indexes. A storage error
 * is not swallowed: the revision fails and is retried with a back-off. Log lines carry ids only.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseAssetText {
    private final ObjectStorage storage;
    private final PdfTextExtractor pdf;
    private final DocxTextExtractor docx;
    private final MarkdownTextExtractor markdown;

    /** The lines of all files of a block in attachment order; empty when none of them has readable text. */
    public List<TextLine> lines(long blockId, List<BlockAsset> assets) {
        List<TextLine> lines = new ArrayList<>();
        for (BlockAsset asset : assets) {
            Optional<ExtractedText> text = extract(blockId, asset);
            if (text.isPresent()) {
                lines.addAll(text.get().lines());
                // Keep two files apart: a chunk must not run from the end of one into the start of the next.
                lines.add(new TextLine(null, 0, ""));
            }
        }

        return lines;
    }

    private Optional<ExtractedText> extract(long blockId, BlockAsset asset) {
        Optional<KnowledgeFileType> type = type(asset);
        if (type.isEmpty() || asset.sizeBytes() > LearningLimits.MAX_PDF_BYTES) {
            log.info("Course file not indexed: unsupported blockId={} assetId={}", blockId, asset.assetId());
            return Optional.empty();
        }
        Path file = null;
        try {
            file = download(asset);
            Path path = file;

            return Optional.of(switch (type.get()) {
                case PDF -> pdf.extract(path);
                case DOCX -> docx.extract(path);
                case MARKDOWN -> markdown.extract(path);
            });
        } catch (ExtractionException exception) {
            // The message is a fixed Vietnamese reason, not file text; it says why this block has no chunks.
            log.info("Course file not indexed: {} blockId={} assetId={}", exception.getMessage(), blockId,
                asset.assetId());
            return Optional.empty();
        } finally {
            delete(file);
        }
    }

    private static Optional<KnowledgeFileType> type(BlockAsset asset) {
        if (MediaType.APPLICATION_PDF_VALUE.equals(asset.mimeType())) {
            return Optional.of(KnowledgeFileType.PDF);
        }

        return KnowledgeFileType.fromFileName(asset.originalName());
    }

    private Path download(BlockAsset asset) {
        ObjectStorage.ObjectInfo info = storage.stat(asset.storageKey());
        Path file = null;
        try {
            file = Files.createTempFile("course-asset-", ".bin");
            if (info.size() > 0) {
                try (InputStream input = storage.read(asset.storageKey(), 0, info.size(), info.etag())) {
                    Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }

            return file;
        } catch (IOException exception) {
            delete(file);
            throw new UncheckedIOException("Cannot read course file", exception);
        } catch (RuntimeException exception) {
            delete(file);
            throw exception;
        }
    }

    private static void delete(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            log.warn("Cannot delete temporary course file type={}", exception.getClass().getName());
        }
    }
}
