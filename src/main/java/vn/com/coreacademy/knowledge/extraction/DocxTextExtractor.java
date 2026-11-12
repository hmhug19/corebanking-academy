package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.config.ExtractionLimits;

/**
 * DOCX has no pages: each paragraph and each table row of the body is one "line" (shown as "đoạn"). Headers, footers
 * and footnotes are not part of the body and are skipped.
 */
@Component
public class DocxTextExtractor {
    private final ExtractionLimits limits;

    public DocxTextExtractor() {
        this(ExtractionLimits.DEFAULTS);
    }

    @Autowired
    public DocxTextExtractor(ExtractionLimits limits) {
        this.limits = limits;
    }

    public ExtractedText extract(Path file) {
        ExtractionGuard guard = new ExtractionGuard(limits);
        try (InputStream input = Files.newInputStream(file); XWPFDocument document = new XWPFDocument(input)) {
            List<TextLine> lines = new ArrayList<>();
            boolean hasText = false;
            for (IBodyElement element : document.getBodyElements()) {
                for (String row : rowsOf(element)) {
                    guard.add(row);
                    hasText |= !row.isBlank();
                    lines.add(new TextLine(null, lines.size() + 1, row));
                }
            }
            if (!hasText) {
                throw ExtractionException.noText();
            }

            guard.checkQuality(lines);

            return new ExtractedText(lines, null);
        } catch (ExtractionException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ExtractionException("Không đọc được nội dung tệp Word. Tệp có thể bị hỏng.", exception);
        }
    }

    private static List<String> rowsOf(IBodyElement element) {
        if (element instanceof XWPFParagraph paragraph) {
            return List.of(oneLine(paragraph.getText()));
        }
        if (element instanceof XWPFTable table) {
            return table.getRows().stream().map(DocxTextExtractor::rowText).toList();
        }
        if (element instanceof XWPFSDT sdt) {
            return List.of(oneLine(sdt.getContent().getText()));
        }

        return List.of();
    }

    private static String rowText(XWPFTableRow row) {
        return row.getTableCells().stream().map(cell -> oneLine(cell.getText())).collect(Collectors.joining(" | "));
    }

    /** Soft breaks and tabs inside a paragraph become single spaces so one paragraph stays one line. */
    private static String oneLine(String text) {
        return text.replaceAll("[\\r\\n\\t]+", " ").strip();
    }
}
