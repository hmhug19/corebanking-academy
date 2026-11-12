package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.com.coreacademy.knowledge.config.ExtractionLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class MarkdownTextExtractorTest {
    private final MarkdownTextExtractor extractor = new MarkdownTextExtractor();

    @TempDir
    Path directory;

    @Test
    void numbersEveryLineFromOneAndCountsBlankLines() throws IOException {
        Path file = write("# Tiêu đề\n\nĐoạn một.\nĐoạn hai.\n");

        ExtractedText text = extractor.extract(file);

        assertThat(text.pageCount()).isNull();
        assertThat(text.lines()).containsExactly(
            new TextLine(null, 1, "# Tiêu đề", List.of("Tiêu đề"), LineKind.HEADING),
            new TextLine(null, 2, "", List.of("Tiêu đề"), LineKind.PARAGRAPH),
            new TextLine(null, 3, "Đoạn một.", List.of("Tiêu đề"), LineKind.PARAGRAPH),
            new TextLine(null, 4, "Đoạn hai.", List.of("Tiêu đề"), LineKind.PARAGRAPH));
    }

    @Test
    void headingsBecomeTheSectionPathAndHashesInsideCodeAreNotHeadings() throws IOException {
        Path file = write("Lời mở đầu.\n# Hệ thống\n## Cài đặt\n- bước một\n2. bước hai\n```bash\n# chạy script\n"
            + "make all\n\n```\n## Vận hành\nXong. ##\n### Sự cố\nGọi trực.\n# Phụ lục\nHết.\n");

        List<TextLine> lines = extractor.extract(file).lines();

        assertThat(lines).extracting(TextLine::text, TextLine::sectionPath, TextLine::kind).containsExactly(
            tuple("Lời mở đầu.", List.of(), LineKind.PARAGRAPH),
            tuple("# Hệ thống", List.of("Hệ thống"), LineKind.HEADING),
            tuple("## Cài đặt", List.of("Hệ thống", "Cài đặt"), LineKind.HEADING),
            tuple("- bước một", List.of("Hệ thống", "Cài đặt"), LineKind.LIST_ITEM),
            tuple("2. bước hai", List.of("Hệ thống", "Cài đặt"), LineKind.LIST_ITEM),
            tuple("```bash", List.of("Hệ thống", "Cài đặt"), LineKind.CODE),
            tuple("# chạy script", List.of("Hệ thống", "Cài đặt"), LineKind.CODE),
            tuple("make all", List.of("Hệ thống", "Cài đặt"), LineKind.CODE),
            tuple("", List.of("Hệ thống", "Cài đặt"), LineKind.CODE),
            tuple("```", List.of("Hệ thống", "Cài đặt"), LineKind.CODE),
            tuple("## Vận hành", List.of("Hệ thống", "Vận hành"), LineKind.HEADING),
            tuple("Xong. ##", List.of("Hệ thống", "Vận hành"), LineKind.PARAGRAPH),
            tuple("### Sự cố", List.of("Hệ thống", "Vận hành", "Sự cố"), LineKind.HEADING),
            tuple("Gọi trực.", List.of("Hệ thống", "Vận hành", "Sự cố"), LineKind.PARAGRAPH),
            tuple("# Phụ lục", List.of("Phụ lục"), LineKind.HEADING),
            tuple("Hết.", List.of("Phụ lục"), LineKind.PARAGRAPH));
    }

    @Test
    void aTableIsMarkedRowByRowIncludingItsSeparator() throws IOException {
        Path file = write("# Phí\nBảng phí:\n\n| Mã | Phí |\n|----|-----|\n| A | 10 |\n| B | 20 |\n\nHết.\n");

        List<TextLine> lines = extractor.extract(file).lines();

        assertThat(lines).extracting(TextLine::kind).containsExactly(LineKind.HEADING, LineKind.PARAGRAPH,
            LineKind.PARAGRAPH, LineKind.TABLE_ROW, LineKind.TABLE_ROW, LineKind.TABLE_ROW, LineKind.TABLE_ROW,
            LineKind.PARAGRAPH, LineKind.PARAGRAPH);
        assertThat(lines).extracting(TextLine::sectionPath).containsOnly(List.of("Phí"));
    }

    @Test
    void windowsAndUnixLineEndingsCountTheSame() throws IOException {
        Path file = write("a\r\nb\nc");

        assertThat(extractor.extract(file).lines()).extracting(TextLine::text).containsExactly("a", "b", "c");
    }

    @Test
    void aByteOrderMarkIsNotPartOfTheFirstLine() throws IOException {
        Path file = write("﻿xin chào\n");

        assertThat(extractor.extract(file).lines().get(0).text()).isEqualTo("xin chào");
    }

    @Test
    void aFileOfOnlyWhitespaceHasNoText() throws IOException {
        Path file = write("  \n\n \n");

        assertThatThrownBy(() -> extractor.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessage("Tài liệu không có nội dung văn bản.");
    }

    @Test
    void invalidUtf8IsRejected() throws IOException {
        Path file = directory.resolve("bad.md");
        Files.write(file, new byte[] {'a', (byte) 0xC3, (byte) 0x28});

        assertThatThrownBy(() -> extractor.extract(file)).isInstanceOf(ExtractionException.class);
    }

    @Test
    void moreThanTheConfiguredCharactersIsRefused() throws IOException {
        Path file = write("Một dòng văn bản bình thường.\n".repeat(10));
        MarkdownTextExtractor small = new MarkdownTextExtractor(
            new ExtractionLimits(300, 100, Duration.ofSeconds(60), 0.5));

        assertThatThrownBy(() -> small.extract(file)).isInstanceOf(ExtractionException.class)
            .hasMessage("Nội dung tài liệu vượt giới hạn 100 ký tự. Hãy tách thành các tệp nhỏ hơn.");
    }

    private Path write(String content) throws IOException {
        Path file = directory.resolve("doc.md");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
