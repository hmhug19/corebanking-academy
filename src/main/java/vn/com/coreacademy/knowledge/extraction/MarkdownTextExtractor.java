package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.config.ExtractionLimits;

/** Markdown lines are the file's own lines: 1-based, blank ones counted, no page. */
@Component
public class MarkdownTextExtractor {
    private static final String BOM = "﻿";

    private final ExtractionLimits limits;

    public MarkdownTextExtractor() {
        this(ExtractionLimits.DEFAULTS);
    }

    @Autowired
    public MarkdownTextExtractor(ExtractionLimits limits) {
        this.limits = limits;
    }

    /** Markdown is strict UTF-8, so it can have no legacy-font garbage: only the size limit applies. */
    public ExtractedText extract(Path file) {
        ExtractionGuard guard = new ExtractionGuard(limits);
        String text = decode(file);
        guard.add(text);
        if (text.startsWith(BOM)) {
            text = text.substring(1);
        }

        String[] rows = text.split("\r\n|\n", -1);
        // A trailing line break ends the last line; it does not start another one.
        int count = rows.length > 0 && rows[rows.length - 1].isEmpty() ? rows.length - 1 : rows.length;

        List<TextLine> lines = new ArrayList<>(count);
        boolean hasText = false;
        for (int i = 0; i < count; i++) {
            hasText |= !rows[i].isBlank();
            lines.add(new TextLine(null, i + 1, rows[i]));
        }
        if (!hasText) {
            throw ExtractionException.noText();
        }

        return new ExtractedText(lines, null);
    }

    private static String decode(Path file) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(file)))
                .toString();
        } catch (CharacterCodingException exception) {
            throw new ExtractionException("Tệp Markdown không phải UTF-8 hợp lệ.", exception);
        } catch (IOException exception) {
            throw new ExtractionException("Không đọc được tệp Markdown.", exception);
        }
    }
}
