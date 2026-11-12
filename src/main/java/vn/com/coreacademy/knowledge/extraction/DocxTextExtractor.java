package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNum;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFStyle;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.config.ExtractionLimits;

/**
 * DOCX has no pages: each paragraph and each table row of the body is one "line" (shown as "đoạn"). Headers, footers
 * and footnotes are not part of the body and are skipped. Heading styles give the section path, automatic list
 * numbers (which {@code getText()} omits) are written into the line, and each table row after the first is prefixed
 * with the header row's cells.
 */
@Component
public class DocxTextExtractor {
    private static final Pattern HEADING_STYLE = Pattern.compile("(?i)^(?:heading|tiêu đề)\\s*(\\d)$");

    private final ExtractionLimits limits;

    public DocxTextExtractor() {
        this(ExtractionLimits.DEFAULTS);
    }

    @Autowired
    public DocxTextExtractor(ExtractionLimits limits) {
        this.limits = limits;
    }

    public ExtractedText extract(Path file) {
        ExtractionGuard guard = new ExtractionGuard(limits);
        try (InputStream input = Files.newInputStream(file); XWPFDocument document = new XWPFDocument(input)) {
            Walk walk = new Walk(document);
            boolean hasText = false;
            for (IBodyElement element : document.getBodyElements()) {
                for (TextLine line : walk.linesOf(element)) {
                    guard.add(line.text());
                    hasText |= !line.text().isBlank();
                    walk.lines.add(line);
                }
            }
            if (!hasText) {
                throw ExtractionException.noText();
            }

            guard.checkQuality(walk.lines);

            return new ExtractedText(walk.lines, null);
        } catch (ExtractionException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ExtractionException("Không đọc được nội dung tệp Word. Tệp có thể bị hỏng.", exception);
        }
    }

    /** Trạng thái một lần đọc: chuỗi tiêu đề hiện hành và bộ đếm đánh số tự động theo từng danh sách. */
    private static final class Walk {
        private final XWPFDocument document;
        private final SectionTracker sections = new SectionTracker();
        private final Map<BigInteger, int[]> counters = new HashMap<>();
        private final List<TextLine> lines = new ArrayList<>();

        Walk(XWPFDocument document) {
            this.document = document;
        }

        List<TextLine> linesOf(IBodyElement element) {
            if (element instanceof XWPFParagraph paragraph) {
                return List.of(paragraphLine(paragraph));
            }
            if (element instanceof XWPFTable table) {
                return tableLines(table);
            }
            if (element instanceof XWPFSDT sdt) {
                return List.of(newLine(0, oneLine(sdt.getContent().getText()), LineKind.PARAGRAPH));
            }

            return List.of();
        }

        /** {@code offset} là số dòng cùng phần tử đã sinh ra nhưng chưa được thêm vào {@code lines}. */
        private TextLine newLine(int offset, String text, LineKind kind) {
            return new TextLine(null, lines.size() + offset + 1, text, sections.path(), kind);
        }

        private TextLine paragraphLine(XWPFParagraph paragraph) {
            String text = oneLine(paragraph.getText());
            int headingLevel = headingLevel(paragraph);
            String marker = paragraph.getNumID() == null ? "" : marker(paragraph);
            if (!marker.isEmpty() && !text.isEmpty()) {
                text = marker + " " + text;
            }
            if (headingLevel > 0 && !text.isEmpty()) {
                sections.enter(headingLevel, text);

                return newLine(0, text, LineKind.HEADING);
            }

            return newLine(0, text, marker.isEmpty() ? LineKind.PARAGRAPH : LineKind.LIST_ITEM);
        }

        /** Cấp tiêu đề (1..9) theo tên hoặc mã style; 0 khi không phải tiêu đề. */
        private int headingLevel(XWPFParagraph paragraph) {
            String styleId = paragraph.getStyleID();
            if (styleId == null) {
                return 0;
            }
            XWPFStyle style = document.getStyles() == null ? null : document.getStyles().getStyle(styleId);
            String styleName = style == null ? null : style.getName();
            for (String name : new String[] {styleName, styleId}) {
                Matcher matcher = name == null ? null : HEADING_STYLE.matcher(name.strip());
                if (matcher != null && matcher.matches()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }

            return 0;
        }

        /** Số thứ tự Word tự sinh ("1.", "a)", "•"); rỗng khi không dựng lại được. */
        private String marker(XWPFParagraph paragraph) {
            BigInteger numId = paragraph.getNumID();
            XWPFNumbering numbering = document.getNumbering();
            XWPFNum num = numbering == null ? null : numbering.getNum(numId);
            if (num == null) {
                return "";
            }
            XWPFAbstractNum abstractNum = numbering.getAbstractNum(num.getCTNum().getAbstractNumId().getVal());
            if (abstractNum == null) {
                return "";
            }
            CTLvl[] levels = abstractNum.getAbstractNum().getLvlArray();
            int level = paragraph.getNumIlvl() == null ? 0 : paragraph.getNumIlvl().intValue();
            if (level < 0 || level >= levels.length || level > 8 || levels[level].getLvlText() == null) {
                return "";
            }

            int[] counter = counters.computeIfAbsent(numId, id -> new int[9]);
            counter[level] = counter[level] == 0 ? startOf(levels[level]) : counter[level] + 1;
            for (int deeper = level + 1; deeper < counter.length; deeper++) {
                counter[deeper] = 0;
            }

            STNumberFormat.Enum format = formatOf(levels[level]);
            if (format == STNumberFormat.BULLET) {
                return "•";
            }
            if (format == STNumberFormat.NONE) {
                return "";
            }
            String text = levels[level].getLvlText().getVal();
            for (int k = 0; k <= level; k++) {
                int value = counter[k] == 0 ? startOf(levels[k]) : counter[k];
                text = text.replace("%" + (k + 1), formatNumber(value, levels[k]));
            }

            return text;
        }

        private List<TextLine> tableLines(XWPFTable table) {
            List<TextLine> rows = new ArrayList<>();
            List<String> headers = List.of();
            for (XWPFTableRow row : table.getRows()) {
                List<String> cells = row.getTableCells().stream().map(Walk::cellText).toList();
                String text;
                if (rows.isEmpty()) {
                    headers = cells;
                    text = String.join(" | ", cells);
                } else {
                    text = prefixed(headers, cells);
                }
                rows.add(newLine(rows.size(), text, LineKind.TABLE_ROW));
            }

            return rows;
        }

        /** "Cột A: giá trị | Cột B: giá trị": dòng của bảng vẫn hiểu được khi chunker tách khỏi dòng tiêu đề. */
        private static String prefixed(List<String> headers, List<String> cells) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) {
                String header = i < headers.size() ? headers.get(i) : "";
                if (cells.get(i).isEmpty()) {
                    continue;
                }
                parts.add(header.isEmpty() ? cells.get(i) : header + ": " + cells.get(i));
            }

            return String.join(" | ", parts);
        }

        private static String cellText(XWPFTableCell cell) {
            return oneLine(cell.getText());
        }
    }

    private static STNumberFormat.Enum formatOf(CTLvl level) {
        return level.getNumFmt() == null ? null : level.getNumFmt().getVal();
    }

    private static int startOf(CTLvl level) {
        return level.getStart() == null ? 1 : level.getStart().getVal().intValue();
    }

    private static String formatNumber(int value, CTLvl level) {
        STNumberFormat.Enum format = formatOf(level);
        if (format == STNumberFormat.LOWER_LETTER) {
            return letters(value).toLowerCase();
        }
        if (format == STNumberFormat.UPPER_LETTER) {
            return letters(value);
        }
        if (format == STNumberFormat.LOWER_ROMAN) {
            return roman(value).toLowerCase();
        }
        if (format == STNumberFormat.UPPER_ROMAN) {
            return roman(value);
        }

        return Integer.toString(value);
    }

    private static String letters(int value) {
        StringBuilder text = new StringBuilder();
        for (int rest = value; rest > 0; rest = (rest - 1) / 26) {
            text.insert(0, (char) ('A' + (rest - 1) % 26));
        }

        return text.toString();
    }

    private static String roman(int value) {
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] symbols = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder text = new StringBuilder();
        int rest = value;
        for (int i = 0; i < values.length; i++) {
            while (rest >= values[i]) {
                text.append(symbols[i]);
                rest -= values[i];
            }
        }

        return text.toString();
    }

    /** Soft breaks and tabs inside a paragraph become single spaces so one paragraph stays one line. */
    private static String oneLine(String text) {
        return text.replaceAll("[\\r\\n\\t]+", " ").strip();
    }
}
