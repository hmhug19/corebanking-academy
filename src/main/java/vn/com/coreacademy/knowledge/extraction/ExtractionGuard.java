package vn.com.coreacademy.knowledge.extraction;

import java.util.List;

import vn.com.coreacademy.knowledge.config.ExtractionLimits;

/** The limits of one extraction run: starts a clock, counts characters, and checks the finished text. */
final class ExtractionGuard {
    private final ExtractionLimits limits;
    private final long startedAt = System.nanoTime();
    private long characters;

    ExtractionGuard(ExtractionLimits limits) {
        this.limits = limits;
    }

    void checkPages(int pageCount) {
        if (pageCount > limits.maxPages()) {
            throw new ExtractionException("Tài liệu có " + pageCount + " trang, vượt giới hạn " + limits.maxPages()
                + " trang. Hãy tách thành các tệp nhỏ hơn.");
        }
    }

    /** Call as text arrives: refuses as soon as the total passes the limit, before the rest is read. */
    void add(String text) {
        characters += text.length();
        if (characters > limits.maxCharacters()) {
            throw new ExtractionException("Nội dung tài liệu vượt giới hạn " + limits.maxCharacters()
                + " ký tự. Hãy tách thành các tệp nhỏ hơn.");
        }
        if (System.nanoTime() - startedAt > limits.maxDuration().toNanos()) {
            throw new ExtractionException("Đọc tài liệu mất hơn " + limits.maxDuration().toSeconds()
                + " giây nên đã bị dừng. Hãy tách thành các tệp nhỏ hơn hoặc đơn giản hơn.");
        }
    }

    /** Refuses text that is mostly not words (legacy fonts) rather than indexing garbage. */
    void checkQuality(List<TextLine> lines) {
        double share = TextQuality.readableWordShare(lines.stream().map(TextLine::text).toList());
        if (share < limits.minReadableWordShare()) {
            throw ExtractionException.unreadableEncoding();
        }
    }
}
