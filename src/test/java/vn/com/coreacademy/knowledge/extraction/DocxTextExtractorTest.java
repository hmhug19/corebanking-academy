package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import vn.com.coreacademy.knowledge.config.ExtractionLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
            new TextLine(null, 4, "Mã | Nội dung | Người duyệt", List.of(), LineKind.TABLE_ROW),
            new TextLine(null, 5, "Mã: ECR-01 | Nội dung: Vá lỗi lãi suất | Người duyệt: Trưởng ban", List.of(),
                LineKind.TABLE_ROW),
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

    @Test
    void headingStylesBecomeTheSectionPathOfEveryLineUnderThem() throws IOException {
        Path file = directory.resolve("sections.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            heading(document, "Heading1", "Quy trình thay đổi");
            document.createParagraph().createRun().setText("Áp dụng cho mọi hệ thống.");
            heading(document, "Heading2", "Chuẩn bị");
            document.createParagraph().createRun().setText("Hồ sơ gồm ba mục.");
            heading(document, "Heading2", "Thực hiện");
            document.createParagraph().createRun().setText("Làm theo thứ tự.");
            heading(document, "Heading1", "Phụ lục");
            document.createParagraph().createRun().setText("Biểu mẫu.");
            save(document, file);
        }

        List<TextLine> lines = extractor.extract(file).lines();

        assertThat(lines).extracting(TextLine::text, TextLine::sectionPath, TextLine::kind).containsExactly(
            tuple("Quy trình thay đổi", List.of("Quy trình thay đổi"), LineKind.HEADING),
            tuple("Áp dụng cho mọi hệ thống.", List.of("Quy trình thay đổi"), LineKind.PARAGRAPH),
            tuple("Chuẩn bị", List.of("Quy trình thay đổi", "Chuẩn bị"), LineKind.HEADING),
            tuple("Hồ sơ gồm ba mục.", List.of("Quy trình thay đổi", "Chuẩn bị"), LineKind.PARAGRAPH),
            tuple("Thực hiện", List.of("Quy trình thay đổi", "Thực hiện"), LineKind.HEADING),
            tuple("Làm theo thứ tự.", List.of("Quy trình thay đổi", "Thực hiện"), LineKind.PARAGRAPH),
            tuple("Phụ lục", List.of("Phụ lục"), LineKind.HEADING),
            tuple("Biểu mẫu.", List.of("Phụ lục"), LineKind.PARAGRAPH));
        assertThat(lines).extracting(TextLine::line).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    @Test
    void automaticListNumberingIsRebuiltInTheLineText() throws IOException {
        Path file = directory.resolve("steps.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            BigInteger list = numbering(document);
            heading(document, "Heading1", "Đóng sổ");
            item(document, list, 0, "Khóa phiên giao dịch.");
            item(document, list, 1, "Kiểm tra số dư.");
            item(document, list, 1, "Ghi nhận chênh lệch.");
            item(document, list, 0, "Chạy bút toán lãi.");
            document.createParagraph().createRun().setText("Hết quy trình.");
            item(document, list, 0, "Bước nối tiếp sau đoạn văn.");
            save(document, file);
        }

        List<TextLine> lines = extractor.extract(file).lines();

        assertThat(lines).extracting(TextLine::text).containsExactly("Đóng sổ", "1. Khóa phiên giao dịch.",
            "a) Kiểm tra số dư.", "b) Ghi nhận chênh lệch.", "2. Chạy bút toán lãi.", "Hết quy trình.",
            "3. Bước nối tiếp sau đoạn văn.");
        assertThat(lines.subList(1, 5)).extracting(TextLine::kind).containsOnly(LineKind.LIST_ITEM);
        assertThat(lines.get(2).sectionPath()).containsExactly("Đóng sổ");
    }

    @Test
    void eachTableRowIsPrefixedWithTheHeaderCellsAndKeepsItsSection() throws IOException {
        Path file = directory.resolve("table.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            heading(document, "Heading1", "Hạn mức");
            XWPFTable table = document.createTable(3, 2);
            table.getRow(0).getCell(0).setText("Loại");
            table.getRow(0).getCell(1).setText("Hạn mức");
            table.getRow(1).getCell(0).setText("Cá nhân");
            table.getRow(1).getCell(1).setText("500 triệu");
            table.getRow(2).getCell(0).setText("Doanh nghiệp");
            table.getRow(2).getCell(1).setText("5 tỷ");
            save(document, file);
        }

        List<TextLine> lines = extractor.extract(file).lines();

        assertThat(lines).extracting(TextLine::text).containsExactly("Hạn mức", "Loại | Hạn mức",
            "Loại: Cá nhân | Hạn mức: 500 triệu", "Loại: Doanh nghiệp | Hạn mức: 5 tỷ");
        assertThat(lines.subList(1, 4)).extracting(TextLine::kind).containsOnly(LineKind.TABLE_ROW);
        assertThat(lines.subList(1, 4)).extracting(TextLine::sectionPath).containsOnly(List.of("Hạn mức"));
    }

    private static void heading(XWPFDocument document, String style, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(style);
        paragraph.createRun().setText(text);
    }

    private static void item(XWPFDocument document, BigInteger numId, int level, String text) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setNumID(numId);
        paragraph.setNumILvl(BigInteger.valueOf(level));
        paragraph.createRun().setText(text);
    }

    /** Level 0 is "1.", level 1 is "a)". */
    private static BigInteger numbering(XWPFDocument document) {
        XWPFNumbering numbering = document.createNumbering();
        CTAbstractNum abstractNum = CTAbstractNum.Factory.newInstance();
        abstractNum.setAbstractNumId(BigInteger.ZERO);
        CTLvl first = abstractNum.addNewLvl();
        first.setIlvl(BigInteger.ZERO);
        first.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
        first.addNewLvlText().setVal("%1.");
        first.addNewStart().setVal(BigInteger.ONE);
        CTLvl second = abstractNum.addNewLvl();
        second.setIlvl(BigInteger.ONE);
        second.addNewNumFmt().setVal(STNumberFormat.LOWER_LETTER);
        second.addNewLvlText().setVal("%2)");
        second.addNewStart().setVal(BigInteger.ONE);
        return numbering.addNum(numbering.addAbstractNum(new XWPFAbstractNum(abstractNum)));
    }

    private static void save(XWPFDocument document, Path file) throws IOException {
        try (OutputStream output = Files.newOutputStream(file)) {
            document.write(output);
        }
    }
}
