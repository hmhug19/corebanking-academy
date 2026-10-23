package vn.com.coreacademy.knowledge.extraction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.exception.KnowledgeResponseCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileTypeDetectorTest {
    private static final String DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final FileTypeDetector detector = new FileTypeDetector(new KnowledgeProperties(Path.of("unused"),
        DataSize.ofMegabytes(20), new KnowledgeProperties.Worker(Duration.ofSeconds(2), 1, 3),
        new KnowledgeProperties.Chunk(600, 800, 80), new KnowledgeProperties.Retrieval(5, 0.35),
        new KnowledgeProperties.Ollama("http://localhost:11434", "chat", "embed", Duration.ofSeconds(5),
            Duration.ofSeconds(180), Duration.ofMinutes(10))));

    @TempDir
    Path directory;

    @Test
    void eachSupportedExtensionMapsToItsType() {
        assertThat(detector.checkMetadata("a.pdf", 10, "application/pdf")).isEqualTo(KnowledgeFileType.PDF);
        assertThat(detector.checkMetadata("A.MD", 10, "text/markdown")).isEqualTo(KnowledgeFileType.MARKDOWN);
        assertThat(detector.checkMetadata("a.docx", 10, DOCX_MIME)).isEqualTo(KnowledgeFileType.DOCX);
    }

    @Test
    void anUnknownExtensionIsUnsupported() {
        assertRefused(() -> detector.checkMetadata("a.exe", 10, null), KnowledgeResponseCode.FILE_TYPE_UNSUPPORTED);
        assertRefused(() -> detector.checkMetadata("noextension", 10, null),
            KnowledgeResponseCode.FILE_TYPE_UNSUPPORTED);
    }

    @Test
    void anEmptyFileIsInvalid() {
        assertRefused(() -> detector.checkMetadata("a.pdf", 0, "application/pdf"), KnowledgeResponseCode.FILE_INVALID);
    }

    @Test
    void aFileOverTheLimitIsTooLarge() {
        assertRefused(() -> detector.checkMetadata("a.pdf", 20L * 1024 * 1024 + 1, "application/pdf"),
            KnowledgeResponseCode.FILE_TOO_LARGE);
        assertThat(detector.checkMetadata("a.pdf", 20L * 1024 * 1024, "application/pdf"))
            .isEqualTo(KnowledgeFileType.PDF);
    }

    @Test
    void aDeclaredTypeThatContradictsTheExtensionIsInvalid() {
        assertRefused(() -> detector.checkMetadata("a.pdf", 10, "image/png"), KnowledgeResponseCode.FILE_INVALID);
        assertRefused(() -> detector.checkMetadata("a.docx", 10, "application/pdf"),
            KnowledgeResponseCode.FILE_INVALID);
    }

    @Test
    void browsersThatSendNothingOrOctetStreamAreAccepted() {
        assertThat(detector.checkMetadata("a.md", 10, null)).isEqualTo(KnowledgeFileType.MARKDOWN);
        assertThat(detector.checkMetadata("a.md", 10, "")).isEqualTo(KnowledgeFileType.MARKDOWN);
        assertThat(detector.checkMetadata("a.md", 10, "application/octet-stream"))
            .isEqualTo(KnowledgeFileType.MARKDOWN);
        assertThat(detector.checkMetadata("a.md", 10, "text/plain; charset=utf-8"))
            .isEqualTo(KnowledgeFileType.MARKDOWN);
    }

    @Test
    void aPdfNeedsTheMagicBytes() throws IOException {
        detector.checkContent(KnowledgeFileType.PDF,
            write("ok.pdf", "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII)));

        assertRefused(() -> detector.checkContent(KnowledgeFileType.PDF,
            write("fake.pdf", "hello".getBytes(StandardCharsets.US_ASCII))), KnowledgeResponseCode.FILE_INVALID);
    }

    @Test
    void aPdfRenamedDocxIsInvalid() throws IOException {
        Path file = write("renamed.docx", "%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII));

        assertRefused(() -> detector.checkContent(KnowledgeFileType.DOCX, file), KnowledgeResponseCode.FILE_INVALID);
    }

    @Test
    void aZipWithoutWordBodyIsNotDocx() throws IOException {
        Path file = write("archive.docx", zip("notes.txt"));

        assertRefused(() -> detector.checkContent(KnowledgeFileType.DOCX, file), KnowledgeResponseCode.FILE_INVALID);
    }

    @Test
    void aZipWithWordBodyIsDocx() throws IOException {
        Path file = write("real.docx", zip("word/document.xml"));

        detector.checkContent(KnowledgeFileType.DOCX, file);
    }

    @Test
    void markdownMustBeStrictUtf8WithoutNul() throws IOException {
        detector.checkContent(KnowledgeFileType.MARKDOWN,
            write("ok.md", "Tiếng Việt\n".getBytes(StandardCharsets.UTF_8)));

        assertRefused(() -> detector.checkContent(KnowledgeFileType.MARKDOWN,
            write("binary.md", new byte[] {'a', 0, 'b'})), KnowledgeResponseCode.FILE_INVALID);
        assertRefused(() -> detector.checkContent(KnowledgeFileType.MARKDOWN,
            write("latin1.md", new byte[] {'a', (byte) 0xE9})), KnowledgeResponseCode.FILE_INVALID);
    }

    private Path write(String name, byte[] bytes) {
        Path file = directory.resolve(name);
        try {
            Files.write(file, bytes);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return file;
    }

    private static byte[] zip(String entryName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(entryName));
            zip.write("x".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void assertRefused(Runnable action, KnowledgeResponseCode code) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
            .extracting(exception -> ((BusinessException) exception).getResponseCode()).isEqualTo(code);
    }
}
