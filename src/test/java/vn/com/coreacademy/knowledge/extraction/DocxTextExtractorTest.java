package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    private static void save(XWPFDocument document, Path file) throws IOException {
        try (OutputStream output = Files.newOutputStream(file)) {
            document.write(output);
        }
    }
}
