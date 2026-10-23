package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** One page at a time, so every line knows its page; line numbers restart at 1 on each page. */
@Component
public class PdfTextExtractor {
    /** Fewer visible characters than this in the whole file means there is no text layer (a scan). */
    private static final int MIN_TEXT_CHARACTERS = 20;

    public ExtractedText extract(Path file) {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");

            int pageCount = document.getNumberOfPages();
            List<TextLine> lines = new ArrayList<>();
            long characters = 0;
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                characters += text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
                addLines(lines, page, text);
            }
            if (characters < MIN_TEXT_CHARACTERS) {
                throw ExtractionException.noTextLayer();
            }

            return new ExtractedText(lines, pageCount);
        } catch (IOException exception) {
            throw ExtractionException.unreadablePdf(exception);
        }
    }

    private static void addLines(List<TextLine> lines, int page, String text) {
        String[] rows = text.split("\n", -1);
        // The stripper ends the last line with a separator; that is not another line.
        int count = rows.length > 0 && rows[rows.length - 1].isEmpty() ? rows.length - 1 : rows.length;
        for (int i = 0; i < count; i++) {
            lines.add(new TextLine(page, i + 1, rows[i]));
        }
    }
}
