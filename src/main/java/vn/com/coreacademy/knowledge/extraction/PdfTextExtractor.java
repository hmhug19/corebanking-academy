package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.config.ExtractionLimits;

/**
 * One page at a time, so every line knows its page; line numbers restart at 1 on each page and keep the number the
 * reader sees even when a repeated header or footer line is dropped. The outline, when the PDF has one, gives the
 * section path.
 */
@Component
public class PdfTextExtractor {
    /** Fewer visible characters than this in the whole file means there is no text layer (a scan). */
    private static final int MIN_TEXT_CHARACTERS = 20;

    /** Số dòng không rỗng ở đầu và cuối trang được xét là vùng header/footer. */
    private static final int EDGE_LINES = 3;

    /** Cần ít nhất ngần này trang mới đủ căn cứ để nói một dòng "lặp ở hầu hết các trang". */
    private static final int MIN_PAGES_FOR_REPEAT = 3;

    private static final Pattern HYPHEN_END = Pattern.compile(".*\\p{L}-$");
    private static final Pattern LOWER_START = Pattern.compile("^\\p{Ll}.*");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\d+[.)]|\\p{L}[.)]|[-•*–])\\s+\\S.*");

    private final ExtractionLimits limits;

    public PdfTextExtractor() {
        this(ExtractionLimits.DEFAULTS);
    }

    @Autowired
    public PdfTextExtractor(ExtractionLimits limits) {
        this.limits = limits;
    }

    public ExtractedText extract(Path file) {
        ExtractionGuard guard = new ExtractionGuard(limits);
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            // PDFBox dùng separator riêng ở cuối trang; chuẩn hóa cả hai để Windows không giữ CR trong dòng cuối.
            stripper.setPageEnd("\n");

            int pageCount = document.getNumberOfPages();
            guard.checkPages(pageCount);
            List<List<String>> pages = new ArrayList<>();
            long characters = 0;
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                guard.add(text);
                characters += text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)).count();
                pages.add(rowsOf(text));
            }
            if (characters < MIN_TEXT_CHARACTERS) {
                throw ExtractionException.noTextLayer();
            }

            List<TextLine> lines = structured(pages, outlineByPage(document));

            guard.checkQuality(lines);

            return new ExtractedText(lines, pageCount);
        } catch (IOException exception) {
            throw ExtractionException.unreadablePdf(exception);
        }
    }

    private static List<String> rowsOf(String text) {
        String[] rows = text.split("\n", -1);
        // The stripper ends the last line with a separator; that is not another line.
        int count = rows.length > 0 && rows[rows.length - 1].isEmpty() ? rows.length - 1 : rows.length;

        return List.of(rows).subList(0, count);
    }

    /** Một dòng của trang với số dòng gốc, để bỏ hoặc nối dòng mà số trích dẫn không đổi nghĩa. */
    private static final class Row {
        private final int line;
        private String text;

        Row(int line, String text) {
            this.line = line;
            this.text = text;
        }
    }

    private record OutlineEntry(int level, String title) {
    }

    private static List<TextLine> structured(List<List<String>> pages, Map<Integer, List<OutlineEntry>> outline) {
        Set<String> repeated = repeatedEdgeKeys(pages);
        SectionTracker sections = new SectionTracker();
        List<TextLine> lines = new ArrayList<>();
        for (int index = 0; index < pages.size(); index++) {
            List<Row> rows = withoutRepeatedEdges(pages.get(index), repeated);
            joinHyphenatedWords(rows);
            List<OutlineEntry> entries = new ArrayList<>(outline.getOrDefault(index, List.of()));
            Map<Row, OutlineEntry> headings = matchHeadings(rows, entries);
            // Mục lục trỏ tới trang nhưng không có dòng tiêu đề tương ứng: mở mục ngay đầu trang.
            entries.forEach(entry -> sections.enter(entry.level(), entry.title()));
            for (Row row : rows) {
                OutlineEntry heading = headings.get(row);
                LineKind kind = LineKind.PARAGRAPH;
                if (heading != null) {
                    sections.enter(heading.level(), heading.title());
                    kind = LineKind.HEADING;
                } else if (LIST_ITEM.matcher(row.text.strip()).matches()) {
                    kind = LineKind.LIST_ITEM;
                }
                lines.add(new TextLine(index + 1, row.line, row.text, sections.path(), kind));
            }
        }

        return lines;
    }

    /** Header, footer và số trang: dòng (số trang chuẩn hóa thành #) xuất hiện ở rìa của hơn nửa số trang. */
    private static Set<String> repeatedEdgeKeys(List<List<String>> pages) {
        Set<String> repeated = new HashSet<>();
        if (pages.size() < MIN_PAGES_FOR_REPEAT) {
            return repeated;
        }
        Map<String, Integer> pagesWithKey = new HashMap<>();
        for (List<String> page : pages) {
            Set<String> keysOfPage = new HashSet<>();
            for (int index : edgeIndexes(page)) {
                keysOfPage.add(edgeKey(page.get(index)));
            }
            keysOfPage.forEach(key -> pagesWithKey.merge(key, 1, Integer::sum));
        }
        pagesWithKey.forEach((key, count) -> {
            if (count * 2 > pages.size()) {
                repeated.add(key);
            }
        });

        return repeated;
    }

    private static List<Integer> edgeIndexes(List<String> page) {
        List<Integer> nonBlank = new ArrayList<>();
        for (int i = 0; i < page.size(); i++) {
            if (!page.get(i).isBlank()) {
                nonBlank.add(i);
            }
        }
        List<Integer> edges = new ArrayList<>();
        if (nonBlank.size() <= 2 * EDGE_LINES) {
            // Trang quá ngắn: vùng đầu và cuối phủ hết trang, một dòng nội dung lặp lại sẽ bị nhận nhầm là header.
            return edges;
        }
        for (int i = 0; i < nonBlank.size(); i++) {
            if (i < EDGE_LINES || i >= nonBlank.size() - EDGE_LINES) {
                edges.add(nonBlank.get(i));
            }
        }

        return edges;
    }

    private static String edgeKey(String text) {
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("\\d+", "#").replaceAll("\\s+", " ");
    }

    private static List<Row> withoutRepeatedEdges(List<String> page, Set<String> repeated) {
        Set<Integer> edges = new HashSet<>(edgeIndexes(page));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < page.size(); i++) {
            if (!(edges.contains(i) && repeated.contains(edgeKey(page.get(i))))) {
                rows.add(new Row(i + 1, page.get(i)));
            }
        }

        return rows;
    }

    /** "interna-" cuối dòng và "tional ..." đầu dòng sau: kéo từ lên dòng trên, dòng dưới giữ phần còn lại. */
    private static void joinHyphenatedWords(List<Row> rows) {
        for (int i = 0; i + 1 < rows.size(); i++) {
            Row row = rows.get(i);
            Row next = rows.get(i + 1);
            String nextText = next.text.strip();
            if (next.line != row.line + 1 || !HYPHEN_END.matcher(row.text.stripTrailing()).matches()
                || !LOWER_START.matcher(nextText).matches()) {
                continue;
            }
            int space = nextText.indexOf(' ');
            String word = space < 0 ? nextText : nextText.substring(0, space);
            String rest = space < 0 ? "" : nextText.substring(space + 1).strip();
            String head = row.text.stripTrailing();
            row.text = head.substring(0, head.length() - 1) + word;
            if (rest.isEmpty()) {
                rows.remove(i + 1);
            } else {
                next.text = rest;
            }
        }
    }

    /** Gán mục lục vào dòng có chữ trùng tiêu đề (theo thứ tự); mục không khớp dòng nào được để lại trong entries. */
    private static Map<Row, OutlineEntry> matchHeadings(List<Row> rows, List<OutlineEntry> entries) {
        Map<Row, OutlineEntry> headings = new HashMap<>();
        for (Row row : rows) {
            String text = normalize(row.text);
            for (OutlineEntry entry : entries) {
                if (!text.isEmpty() && text.equals(normalize(entry.title()))) {
                    headings.put(row, entry);
                    entries.remove(entry);
                    break;
                }
            }
        }

        return headings;
    }

    private static String normalize(String text) {
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Mục lục (outline) của PDF, theo chỉ số trang bắt đầu từ 0; mục không trỏ được tới trang thì bỏ qua. */
    private static Map<Integer, List<OutlineEntry>> outlineByPage(PDDocument document) {
        Map<Integer, List<OutlineEntry>> byPage = new HashMap<>();
        PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
        if (outline != null) {
            collect(document, outline, 1, byPage);
        }

        return byPage;
    }

    private static void collect(PDDocument document, PDOutlineNode node, int level,
        Map<Integer, List<OutlineEntry>> byPage) {
        for (PDOutlineItem item : node.children()) {
            try {
                PDPage page = item.findDestinationPage(document);
                String title = item.getTitle() == null ? "" : item.getTitle().strip();
                int index = page == null ? -1 : document.getPages().indexOf(page);
                if (index >= 0 && !title.isEmpty()) {
                    byPage.computeIfAbsent(index, key -> new ArrayList<>()).add(new OutlineEntry(level, title));
                }
            } catch (IOException exception) {
                // Mục trỏ tới đích hỏng: chỉ mất tên mục này, nội dung vẫn đọc được.
            }
            collect(document, item, level + 1, byPage);
        }
    }
}
