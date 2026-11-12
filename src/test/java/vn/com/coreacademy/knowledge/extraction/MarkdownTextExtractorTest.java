package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.com.coreacademy.knowledge.config.ExtractionLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            new TextLine(null, 1, "# Tiêu đề"),
            new TextLine(null, 2, ""),
            new TextLine(null, 3, "Đoạn một."),
            new TextLine(null, 4, "Đoạn hai."));
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
