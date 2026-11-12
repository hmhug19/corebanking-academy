package vn.com.coreacademy.assistant.eval;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFNumbering;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;

/**
 * Writes the fixture documents of {@link RetrievalSet} as real files (PDF, DOCX, Markdown) so the eval can feed them to
 * the real extractors. PDFs need a TrueType font with Vietnamese letters: {@code -Dretrieval.eval.font=path}, or one of
 * the usual system fonts.
 */
public final class RetrievalFixtures {
    private static final List<String> FONTS = List.of(
        "C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/times.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans.ttf", "/Library/Fonts/Arial.ttf", "/System/Library/Fonts/Supplemental/Arial.ttf");
    private static final float MARGIN = 56;
    private static final float SIZE = 11;
    private static final float LEADING = 15;

    /** The font PDFs are drawn with, when this machine has one. */
    public static Optional<Path> font() {
        String configured = System.getProperty("retrieval.eval.font");
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured);
        }
        candidates.addAll(FONTS);
        return candidates.stream().map(Path::of).filter(Files::isRegularFile).findFirst();
    }

    /** Writes {@code doc} into {@code directory} under its file name. */
    public static Path write(RetrievalSet.Doc doc, Path directory) throws IOException {
        Path file = directory.resolve(doc.file());
        switch (doc.format()) {
            case "md" -> Files.writeString(file, markdown(doc), StandardCharsets.UTF_8);
            case "docx" -> docx(doc, file);
            case "pdf1" -> pdf(doc, file, false);
            case "pdf2" -> pdf(doc, file, true);
            default -> throw new IllegalArgumentException("Unknown fixture format " + doc.format());
        }
        return file;
    }

    static String markdown(RetrievalSet.Doc doc) {
        StringBuilder text = new StringBuilder("# ").append(doc.file()).append("\n\n");
        for (RetrievalSet.Section section : doc.sections()) {
            text.append("## ").append(section.title()).append("\n\n");
            section.paragraphs().forEach(paragraph -> text.append(paragraph).append("\n\n"));
            if (section.steps() != null) {
                for (int i = 0; i < section.steps().size(); i++) {
                    text.append(i + 1).append(". ").append(section.steps().get(i)).append('\n');
                }
                text.append('\n');
            }
            if (section.table() != null) {
                for (int row = 0; row < section.table().size(); row++) {
                    text.append("| ").append(String.join(" | ", section.table().get(row))).append(" |\n");
                    if (row == 0) {
                        text.append("|").append(" --- |".repeat(section.table().get(0).size())).append('\n');
                    }
                }
                text.append('\n');
            }
        }
        return text.toString();
    }

    private static void docx(RetrievalSet.Doc doc, Path file) throws IOException {
        try (XWPFDocument word = new XWPFDocument(); OutputStream out = Files.newOutputStream(file)) {
            BigInteger numberId = decimalList(word);
            for (RetrievalSet.Section section : doc.sections()) {
                XWPFParagraph heading = word.createParagraph();
                heading.setStyle("Heading1");
                heading.createRun().setText(section.title());
                section.paragraphs().forEach(paragraph -> word.createParagraph().createRun().setText(paragraph));
                if (section.steps() != null) {
                    for (String step : section.steps()) {
                        XWPFParagraph item = word.createParagraph();
                        item.setNumID(numberId);
                        item.createRun().setText(step);
                    }
                }
                if (section.table() != null) {
                    XWPFTable table = word.createTable(section.table().size(), section.table().get(0).size());
                    for (int r = 0; r < section.table().size(); r++) {
                        XWPFTableRow row = table.getRow(r);
                        for (int c = 0; c < section.table().get(r).size(); c++) {
                            row.getCell(c).setText(section.table().get(r).get(c));
                        }
                    }
                }
            }
            word.write(out);
        }
    }

    private static BigInteger decimalList(XWPFDocument word) {
        XWPFNumbering numbering = word.createNumbering();
        CTAbstractNum abstractNum = CTAbstractNum.Factory.newInstance();
        abstractNum.setAbstractNumId(BigInteger.ZERO);
        CTLvl level = abstractNum.addNewLvl();
        level.setIlvl(BigInteger.ZERO);
        level.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
        level.addNewLvlText().setVal("%1.");
        level.addNewStart().setVal(BigInteger.ONE);
        BigInteger abstractId = numbering.addAbstractNum(new XWPFAbstractNum(abstractNum));
        return numbering.addNum(abstractId);
    }

    /** One column of wrapped lines, or two columns whose sections sit side by side, with tables as aligned rows. */
    private static void pdf(RetrievalSet.Doc doc, Path file, boolean twoColumns) throws IOException {
        Path fontFile = font().orElseThrow(() -> new IllegalStateException(
            "No TrueType font with Vietnamese letters found; pass -Dretrieval.eval.font=<path to .ttf>"));
        try (PDDocument pdf = new PDDocument()) {
            PDType0Font font = PDType0Font.load(pdf, fontFile.toFile());
            float pageWidth = PDRectangle.A4.getWidth();
            float columnWidth = twoColumns ? (pageWidth - 3 * MARGIN) / 2 : pageWidth - 2 * MARGIN;
            List<List<String>> columns = new ArrayList<>(List.of(new ArrayList<>(), new ArrayList<>()));
            int sectionNumber = 0;
            for (RetrievalSet.Section section : doc.sections()) {
                List<String> column = columns.get(twoColumns ? sectionNumber % 2 : 0);
                column.add("#" + section.title());
                for (String paragraph : section.paragraphs()) {
                    column.addAll(wrap(font, paragraph, columnWidth));
                }
                if (section.steps() != null) {
                    for (int i = 0; i < section.steps().size(); i++) {
                        column.addAll(wrap(font, (i + 1) + ". " + section.steps().get(i), columnWidth));
                    }
                }
                if (section.table() != null) {
                    for (List<String> row : section.table()) {
                        column.addAll(wrap(font, String.join("   ", row), columnWidth));
                    }
                }
                column.add("");
                sectionNumber++;
            }
            int rows = twoColumns ? Math.max(columns.get(0).size(), columns.get(1).size()) : columns.get(0).size();
            int perPage = (int) ((PDRectangle.A4.getHeight() - 2 * MARGIN) / LEADING);
            for (int from = 0; from < Math.max(rows, 1); from += perPage) {
                PDPage page = new PDPage(PDRectangle.A4);
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    for (int c = 0; c < (twoColumns ? 2 : 1); c++) {
                        List<String> lines = columns.get(c);
                        float x = MARGIN + c * (columnWidth + MARGIN);
                        for (int i = from; i < Math.min(from + perPage, lines.size()); i++) {
                            String line = lines.get(i);
                            if (line.isEmpty()) {
                                continue;
                            }
                            content.beginText();
                            content.setFont(font, SIZE);
                            content.newLineAtOffset(x, PDRectangle.A4.getHeight() - MARGIN - (i - from) * LEADING);
                            content.showText(line.startsWith("#") ? line.substring(1) : line);
                            content.endText();
                        }
                    }
                }
            }
            pdf.save(file.toFile());
        }
    }

    private static List<String> wrap(PDType0Font font, String text, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.getStringWidth(candidate) / 1000 * SIZE > width && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    private RetrievalFixtures() {
    }
}
