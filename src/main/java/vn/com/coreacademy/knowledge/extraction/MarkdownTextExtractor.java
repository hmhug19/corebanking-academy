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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.config.ExtractionLimits;

/** Markdown lines are the file's own lines: 1-based, blank ones counted, no page. */
@Component
public class MarkdownTextExtractor {
    private static final String BOM = "﻿";

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(```|~~~).*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)(?:\\s+#+)?\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*([-*+]|\\d+[.)])\\s+");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
        "^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");

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
        SectionTracker sections = new SectionTracker();
        boolean inFence = false;
        boolean previousIsTable = false;
        for (int i = 0; i < count; i++) {
            String row = rows[i];
            hasText |= !row.isBlank();
            LineKind kind = LineKind.PARAGRAPH;
            if (inFence || FENCE.matcher(row).matches()) {
                // Dòng mở, thân và dòng đóng là một khối liền để chunker không cắt giữa chừng.
                kind = LineKind.CODE;
                if (FENCE.matcher(row).matches()) {
                    inFence = !inFence;
                }
                previousIsTable = false;
            } else {
                Matcher heading = HEADING.matcher(row);
                boolean table = isTableRow(row, i + 1 < count ? rows[i + 1] : "", previousIsTable);
                if (heading.matches()) {
                    kind = LineKind.HEADING;
                    sections.enter(heading.group(1).length(), heading.group(2).strip());
                } else if (table) {
                    kind = LineKind.TABLE_ROW;
                } else if (LIST_ITEM.matcher(row).find()) {
                    kind = LineKind.LIST_ITEM;
                }
                previousIsTable = table;
            }
            lines.add(new TextLine(null, i + 1, row, sections.path(), kind));
        }
        if (!hasText) {
            throw ExtractionException.noText();
        }

        return new ExtractedText(lines, null);
    }

    /** Dòng thuộc bảng: bắt đầu bằng "|", đứng ngay trước dòng phân cách, hoặc nối tiếp bảng đang mở. */
    private static boolean isTableRow(String row, String next, boolean previousIsTable) {
        if (row.isBlank()) {
            return false;
        }
        if (row.stripLeading().startsWith("|")) {
            return true;
        }
        boolean hasPipe = row.indexOf('|') >= 0;

        return hasPipe && (previousIsTable || TABLE_SEPARATOR.matcher(next).matches())
            || previousIsTable && TABLE_SEPARATOR.matcher(row).matches();
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
