package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.com.coreacademy.knowledge.config.ExtractionLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocxTextExtractorTest {
    private final DocxTextExtractor extractor = new DocxTextExtractor();

    @TempDir
    Path directory;

    @Test
    void eachParagraphAndEachTableRowIsOneNumberedLine() throws IOException {
        Path file = directory.resolve("doc.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Quy trình thay đổi khẩn cấp");
            document.createParagraph();
            document.createParagraph().createRun().setText("Bước 1: Lập phiếu ECR.");
            XWPFTable table = document.createTable(2, 3);
            table.getRow(0).getCell(0).setText("Mã");
            table.getRow(0).getCell(1).setText("Nội dung");
            table.getRow(0).getCell(2).setText("Người duyệt");
            table.getRow(1).getCell(0).setText("ECR-01");
            table.getRow(1).getCell(1).setText("Vá lỗi lãi suất");
            table.getRow(1).getCell(2).setText("Trưởng ban");
            document.createParagraph().createRun().setText("Hết.");
            save(document, file);
        }

        ExtractedText text = extractor.extract(file);

        assertThat(text.pageCount()).isNull();
        assertThat(text.lines()).containsExactly(
            new TextLine(null, 1, "Quy trình thay đổi khẩn cấp"),
            new TextLine(null, 2, ""),
            new TextLine(null, 3, "Bước 1: Lập phiếu ECR."),
            new TextLine(null, 4, "Mã | Nội dung | Người duyệt"),
            new TextLine(null, 5, "ECR-01 | Vá lỗi lãi suất | Trưởng ban"),
            new TextLine(null, 6, "Hết."));
    }

    @Test
    void softBreaksBecomeSpacesAndHeadersAreSkipped() throws IOException {
        Path file = directory.resolve("doc.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("dòng một");
            run.addBreak();
            run.setText("dòng hai");
            XWPFHeaderFooterPolicy policy = document.createHeaderFooterPolicy();
            policy.createHeader(XWPFHeaderFooterPolicy.DEFAULT).createParagraph().createRun().setText("HEADER");
            save(document, file);
        }

        assertThat(extractor.extract(file).lines()).containsExactly(new TextLine(null, 1, "dòng một dòng hai"));
    }

    @Test
    void aDocumentWithoutTextHasNoContent() throws IOException {
        Path file = directory.resolve("empty.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph();
            save(document, file);
        }

        assertThatThrownBy(() -> extractor.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessage("Tài liệu không có nội dung văn bản.");
    }

    @Test
    void aFileThatIsNotWordIsUnreadable() throws IOException {
        Path file = directory.resolve("fake.docx");
        Files.writeString(file, "plain text");

        assertThatThrownBy(() -> extractor.extract(file)).isInstanceOf(ExtractionException.class);
    }

    @Test
    void moreThanTheConfiguredCharactersIsRefused() throws IOException {
        Path file = directory.resolve("long.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            for (int i = 0; i < 5; i++) {
                document.createParagraph().createRun().setText("Một đoạn văn bản bình thường.");
            }
            save(document, file);
        }
        DocxTextExtractor small = new DocxTextExtractor(
            new ExtractionLimits(300, 40, Duration.ofSeconds(60), 0.5));

        assertThatThrownBy(() -> small.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessage("Nội dung tài liệu vượt giới hạn 40 ký tự. Hãy tách thành các tệp nhỏ hơn.");
    }

    @Test
    void anExtractionThatRunsTooLongIsStopped() throws IOException {
        Path file = directory.resolve("slow.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Một đoạn văn bản bình thường.");
            save(document, file);
        }
        DocxTextExtractor impatient = new DocxTextExtractor(
            new ExtractionLimits(300, 1_500_000, Duration.ofNanos(1), 0.5));

        assertThatThrownBy(() -> impatient.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessageStartingWith("Đọc tài liệu mất hơn ");
    }

    @Test
    void textInALegacyVietnameseFontIsRefusedAsUnreadable() throws IOException {
        Path file = directory.resolve("tcvn3.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Quy tr×nh ®ãng sæ cuèi ngµy");
            document.createParagraph().createRun().setText("Hä thèng qu¶n lý tµi kho¶n kh¸ch hµng");
            save(document, file);
        }

        assertThatThrownBy(() -> extractor.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessage("Tệp có phông chữ hoặc bảng mã không đọc được");
    }

    private static void save(XWPFDocument document, Path file) throws IOException {
        try (OutputStream output = Files.newOutputStream(file)) {
            document.write(output);
        }
    }
}
