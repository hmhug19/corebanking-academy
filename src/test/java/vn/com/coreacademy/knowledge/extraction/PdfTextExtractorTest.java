package vn.com.coreacademy.knowledge.extraction;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.com.coreacademy.assistant.eval.RetrievalFixtures;
import vn.com.coreacademy.knowledge.config.ExtractionLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void aLineRepeatedAtTheTopOrBottomOfMostPagesIsDropped() throws IOException {
        Path file = pdf(List.of(
            page("Opening the day", "Page 1 of 4"), page("Closing the day", "Page 2 of 4"),
            page("Month end", "Page 3 of 4"), page("Audit trail", "Page 4 of 4")));

        List<TextLine> lines = extractor.extract(file).lines();

        assertThat(lines).hasSize(4 * 6);
        assertThat(lines).extracting(TextLine::text).noneMatch(text -> text.startsWith("Core Academy")
            || text.startsWith("Page "));
        assertThat(lines).extracting(TextLine::text).contains("Opening the day", "Audit trail");
        // Citations keep pointing at the line the reader sees on the page: the header was line 1.
        assertThat(lines.get(0).line()).isEqualTo(2);
        assertThat(lines.get(5).line()).isEqualTo(7);
    }

    private static List<String> page(String title, String footer) {
        List<String> rows = new ArrayList<>(List.of("Core Academy - Internal", title));
        for (String detail : List.of("First", "Second", "Third", "Fourth", "Fifth")) {
            rows.add(detail + " detail of " + title);
        }
        rows.add(footer);

        return rows;
    }

    @Test
    void aWordHyphenatedAtTheEndOfALineIsJoined() throws IOException {
        Path file = pdf(List.of(List.of("The interna-", "tional rate applies today", "A well-known case")));

        List<TextLine> lines = extractor.extract(file).lines();

        assertThat(lines).extracting(TextLine::text).containsExactly("The international",
            "rate applies today", "A well-known case");
    }

    @Test
    void theOutlineGivesTheSectionPathOfEveryLine() throws IOException {
        Path file = directory.resolve("outline.pdf");
        try (PDDocument document = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            List<PDPage> pages = new ArrayList<>();
            for (List<String> rows : List.of(
                List.of("Chapter One", "Intro text", "Setup", "Install it"),
                List.of("More setup text", "Operations", "1. Start the batch"))) {
                PDPage page = new PDPage();
                document.addPage(page);
                pages.add(page);
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
            PDDocumentOutline outline = new PDDocumentOutline();
            document.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem chapter = outlineItem("Chapter One", pages.get(0));
            outline.addLast(chapter);
            chapter.addLast(outlineItem("Setup", pages.get(0)));
            chapter.addLast(outlineItem("Operations", pages.get(1)));
            document.save(file.toFile());
        }

        List<TextLine> lines = extractor.extract(file).lines();

        assertThat(lines).extracting(TextLine::text, TextLine::sectionPath, TextLine::kind).containsExactly(
            tuple("Chapter One", List.of("Chapter One"), LineKind.HEADING),
            tuple("Intro text", List.of("Chapter One"), LineKind.PARAGRAPH),
            tuple("Setup", List.of("Chapter One", "Setup"), LineKind.HEADING),
            tuple("Install it", List.of("Chapter One", "Setup"), LineKind.PARAGRAPH),
            tuple("More setup text", List.of("Chapter One", "Setup"), LineKind.PARAGRAPH),
            tuple("Operations", List.of("Chapter One", "Operations"), LineKind.HEADING),
            tuple("1. Start the batch", List.of("Chapter One", "Operations"), LineKind.LIST_ITEM));
    }

    private static PDOutlineItem outlineItem(String title, PDPage page) {
        PDOutlineItem item = new PDOutlineItem();
        item.setTitle(title);
        PDPageFitDestination destination = new PDPageFitDestination();
        destination.setPage(page);
        item.setDestination(destination);
        return item;
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

    @Test
    void moreThanTheConfiguredPagesIsRefusedBeforeReadingThem() throws IOException {
        Path file = pdf(pagesOf(4, "A line of ordinary text on this page."));
        PdfTextExtractor small = new PdfTextExtractor(limits(3, 1_500_000, Duration.ofSeconds(60)));

        assertThatThrownBy(() -> small.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessage("Tài liệu có 4 trang, vượt giới hạn 3 trang. Hãy tách thành các tệp nhỏ hơn.");
    }

    @Test
    void moreThanTheConfiguredCharactersIsRefused() throws IOException {
        Path file = pdf(pagesOf(3, "A line of ordinary text on this page."));
        PdfTextExtractor small = new PdfTextExtractor(limits(300, 50, Duration.ofSeconds(60)));

        assertThatThrownBy(() -> small.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessage("Nội dung tài liệu vượt giới hạn 50 ký tự. Hãy tách thành các tệp nhỏ hơn.");
    }

    @Test
    void anExtractionThatRunsTooLongIsStopped() throws IOException {
        Path file = pdf(pagesOf(5, "A line of ordinary text on this page."));
        PdfTextExtractor impatient = new PdfTextExtractor(limits(300, 1_500_000, Duration.ofNanos(1)));

        assertThatThrownBy(() -> impatient.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessageStartingWith("Đọc tài liệu mất hơn ").hasMessageContaining("giây nên đã bị dừng");
    }

    @Test
    void aTextLayerInALegacyVietnameseFontIsRefusedAsUnreadable() throws IOException {
        // TCVN3 text as the PDF text layer holds it: Latin-1 symbols standing for Vietnamese letters.
        Path file = pdf(List.of(List.of(
            "Quy tr×nh ®ãng sæ cuèi ngµy", "Hä thèng qu¶n lý tµi kho¶n kh¸ch hµng",
            "B­íc 1: kiÓm tra c¸c giao dÞch ch­a h¹ch to¸n", "B­íc 2: ch¹y bót to¸n lµm trßn l·i suÊt")));

        assertThatThrownBy(() -> extractor.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessage("Tệp có phông chữ hoặc bảng mã không đọc được");
    }

    @Test
    void aFiftyPageVietnamesePdfStillExtracts() throws IOException {
        var fontFile = RetrievalFixtures.font();
        assumeTrue(fontFile.isPresent(), "no TrueType font with Vietnamese letters (-Dretrieval.eval.font=<path>)");
        Path file = directory.resolve("tieng-viet.pdf");
        try (PDDocument document = new PDDocument()) {
            PDType0Font font = PDType0Font.load(document, fontFile.get().toFile());
            for (int number = 1; number <= 50; number++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 12);
                    content.setLeading(16);
                    content.newLineAtOffset(50, 700);
                    for (String row : List.of("Quy trình đóng sổ cuối ngày, trang " + number,
                        "Hệ thống quản lý tài khoản khách hàng của ngân hàng",
                        "Bước một: kiểm tra các giao dịch chưa hạch toán và báo cáo trưởng ban",
                        "Bước hai: chạy bút toán làm tròn lãi suất theo quy định hiện hành")) {
                        content.showText(row);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(file.toFile());
        }

        ExtractedText text = extractor.extract(file);

        assertThat(text.pageCount()).isEqualTo(50);
        assertThat(text.lines()).hasSize(200);
    }

    private static ExtractionLimits limits(int pages, long characters, Duration duration) {
        return new ExtractionLimits(pages, characters, duration, 0.5);
    }

    private static List<List<String>> pagesOf(int count, String line) {
        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            pages.add(List.of(line, line));
        }
        return pages;
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
