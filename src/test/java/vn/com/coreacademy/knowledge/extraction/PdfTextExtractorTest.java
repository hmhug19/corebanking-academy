package vn.com.coreacademy.knowledge.extraction;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfTextExtractorTest {
    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @TempDir
    Path directory;

    @Test
    void numbersPagesFromOneAndRestartsLinesOnEachPage() throws IOException {
        Path file = pdf(List.of(
            List.of("End of day checklist", "Lock the teller sessions", "Run the interest batch"),
            List.of("Second page starts here", "and has two lines")));

        ExtractedText text = extractor.extract(file);

        assertThat(text.pageCount()).isEqualTo(2);
        assertThat(text.lines()).containsExactly(
            new TextLine(1, 1, "End of day checklist"),
            new TextLine(1, 2, "Lock the teller sessions"),
            new TextLine(1, 3, "Run the interest batch"),
            new TextLine(2, 1, "Second page starts here"),
            new TextLine(2, 2, "and has two lines"));
    }

    @Test
    void aPdfThatIsOnlyAnImageHasNoTextLayer() throws IOException {
        Path file = directory.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            BufferedImage image = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
            image.getGraphics().setColor(Color.BLACK);
            image.getGraphics().fillRect(0, 0, 20, 20);
            PDImageXObject picture = LosslessFactory.createFromImage(document, image);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(picture, 50, 600);
            }
            document.save(file.toFile());
        }

        assertThatThrownBy(() -> extractor.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessage("PDF không có lớp văn bản (có thể là bản scan). Hệ thống chưa hỗ trợ nhận dạng chữ.");
    }

    @Test
    void aCorruptPdfIsReportedAsUnreadable() throws IOException {
        Path file = directory.resolve("broken.pdf");
        Files.write(file, "%PDF-1.7 this is not really a pdf".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> extractor.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessage("Không đọc được nội dung PDF. Tệp có thể bị hỏng hoặc được đặt mật khẩu.");
    }

    private Path pdf(List<List<String>> pages) throws IOException {
        Path file = directory.resolve("doc.pdf");
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> rows : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.setLeading(16);
                    content.newLineAtOffset(50, 700);
                    for (String row : rows) {
                        content.showText(row);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(file.toFile());
        }
        return file;
    }
}
