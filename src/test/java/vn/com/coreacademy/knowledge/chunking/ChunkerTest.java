package vn.com.coreacademy.knowledge.chunking;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.extraction.TextLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ChunkerTest {
    @Test
    void worksTheExampleInTheArchitectureDocument() {
        // Section 7 of docs/architecture/ai-assistant-rag.html: target 50, max 65, overlap 20, page 3.
        List<String> texts = List.of(
            "Quy trình cuối ngày (EOD) – Chuẩn bị",
            "Trước khi chạy EOD, bộ phận vận hành phải đối chiếu số dư tài khoản GL với báo cáo tổng hợp.",
            "Mọi chênh lệch lớn hơn 0 đồng phải được giải trình và phê duyệt.",
            "",
            "Khóa giao dịch quầy: thông báo các chi nhánh trước 17:00.",
            "Chặn đăng nhập mới của giao dịch viên sau 17:15.",
            "Kiểm tra hàng đợi giao dịch treo trên kênh điện tử.",
            "Xác nhận lô lãi tiền gửi đã tính xong trong ngày.",
            "",
            "Chạy EOD theo thứ tự: tiền gửi, cho vay, thẻ, sổ cái.",
            "Theo dõi nhật ký job; dừng ngay khi có lỗi mức nghiêm trọng.",
            "Lập biên bản hoàn tất và gửi trưởng ca trước 23:00.");

        List<Chunk> chunks = chunker(50, 65, 20).chunk(lines(3, texts));

        assertThat(chunks).extracting(Chunk::lineStart, Chunk::lineEnd, Chunk::tokenEstimate)
            .containsExactly(
                tuple(1, 3, 65),
                tuple(5, 7, 52),
                tuple(7, 10, 52),
                tuple(10, 12, 55));
        assertThat(chunks).extracting(Chunk::page).containsOnly(3);
        assertThat(chunks.get(2).content()).startsWith("Kiểm tra hàng đợi").contains("Chạy EOD theo thứ tự");
    }

    @Test
    void textUnderTheTargetIsOneChunkWithTrueLineNumbers() {
        List<Chunk> chunks = chunker(600, 800, 80).chunk(List.of(
            new TextLine(null, 3, "first"), new TextLine(null, 4, ""), new TextLine(null, 5, "last"),
            new TextLine(null, 6, "   ")));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).lineStart()).isEqualTo(3);
        assertThat(chunks.get(0).lineEnd()).isEqualTo(5);
        assertThat(chunks.get(0).content()).isEqualTo("first\nlast");
        assertThat(chunks.get(0).page()).isNull();
    }

    @Test
    void aChunkNeverSpansTwoPagesAndOverlapDoesNotCrossThem() {
        List<TextLine> lines = List.of(
            new TextLine(1, 1, "alpha"), new TextLine(1, 2, "beta"),
            new TextLine(2, 1, "gamma"), new TextLine(2, 2, "delta"));

        List<Chunk> chunks = chunker(600, 800, 80).chunk(lines);

        assertThat(chunks).extracting(Chunk::page).containsExactly(1, 2);
        assertThat(chunks.get(0).content()).isEqualTo("alpha\nbeta");
        assertThat(chunks.get(1).content()).isEqualTo("gamma\ndelta");
        assertThat(chunks.get(1).lineStart()).isEqualTo(1);
    }

    @Test
    void aLineThatWouldPassTheMaximumStartsTheNextChunkWithOverlap() {
        // 24-char lines are 8 tokens each; target 100 is out of reach, max 20 holds two lines.
        List<TextLine> lines = lines(null, List.of("a".repeat(24), "b".repeat(24), "c".repeat(24), "d".repeat(24)));

        List<Chunk> chunks = chunker(100, 20, 8).chunk(lines);

        assertThat(chunks).extracting(Chunk::lineStart, Chunk::lineEnd).containsExactly(
            tuple(1, 2),
            tuple(2, 3),
            tuple(3, 4));
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.tokenEstimate()).isLessThanOrEqualTo(20));
    }

    @Test
    void aVeryLongLineIsSplitOnWhitespaceKeepingItsLineNumber() {
        String words = String.join(" ", Collections.nCopies(60, "word"));
        List<TextLine> lines = List.of(new TextLine(2, 7, "before"), new TextLine(2, 8, words),
            new TextLine(2, 9, "after"));

        List<Chunk> chunks = chunker(20, 40, 5).chunk(lines);

        List<Chunk> pieces = chunks.stream().filter(chunk -> chunk.lineStart() == 8).toList();
        assertThat(pieces.size()).isGreaterThan(1);
        assertThat(pieces).allSatisfy(piece -> {
            assertThat(piece.lineEnd()).isEqualTo(8);
            assertThat(piece.tokenEstimate()).isLessThanOrEqualTo(40);
            assertThat(piece.page()).isEqualTo(2);
        });
        assertThat(String.join(" ", pieces.stream().map(Chunk::content).toList())).isEqualTo(words);
        assertThat(chunks.get(0).content()).isEqualTo("before");
        assertThat(chunks.get(chunks.size() - 1).content()).isEqualTo("after");
    }

    @Test
    void aSingleWordLongerThanTheLimitIsCutByCharacters() {
        List<Chunk> chunks = chunker(10, 20, 0).chunk(List.of(new TextLine(null, 1, "x".repeat(100))));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length()).isLessThanOrEqualTo(30));
        assertThat(String.join("", chunks.stream().map(Chunk::content).toList())).isEqualTo("x".repeat(100));
    }

    @Test
    void noLinesMeansNoChunks() {
        assertThat(chunker(600, 800, 80).chunk(List.of())).isEmpty();
        assertThat(chunker(600, 800, 80).chunk(List.of(new TextLine(null, 1, " ")))).isEmpty();
    }

    @Test
    void theLastChunkNeverConsistsOfOverlapOnly() {
        // Two 8-token lines reach the target of 16 and close the chunk; its overlap must not become a chunk.
        List<Chunk> chunks = chunker(16, 40, 8).chunk(lines(null, List.of("a".repeat(24), "b".repeat(24))));

        assertThat(chunks).hasSize(1);
    }

    private static Chunker chunker(int target, int max, int overlap) {
        return new Chunker(new KnowledgeProperties(Path.of("unused"), DataSize.ofMegabytes(20),
            new KnowledgeProperties.Worker(Duration.ofSeconds(2), 1, 3),
            new KnowledgeProperties.Chunk(target, max, overlap), new KnowledgeProperties.Retrieval(5, 0.35),
            new KnowledgeProperties.Ollama("http://localhost:11434", "chat", "embed", Duration.ofSeconds(5),
                Duration.ofSeconds(180), Duration.ofMinutes(10))));
    }

    private static List<TextLine> lines(Integer page, List<String> texts) {
        List<TextLine> lines = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            lines.add(new TextLine(page, i + 1, texts.get(i)));
        }
        return lines;
    }
}
