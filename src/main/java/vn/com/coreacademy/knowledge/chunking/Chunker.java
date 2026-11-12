package vn.com.coreacademy.knowledge.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.extraction.LineKind;
import vn.com.coreacademy.knowledge.extraction.TextLine;

/**
 * Cuts text at section boundaries first, then by size, always at line boundaries so a chunk knows its page and line
 * range. A table or a code block is never cut unless it alone exceeds the maximum; a table that does is split by rows
 * and every part repeats the header row. Overlap is kept across page breaks. The same algorithm, with smaller limits,
 * runs in section 7 of docs/architecture/ai-assistant-rag.html; keep the two in step.
 */
@Component
@RequiredArgsConstructor
public class Chunker {
    // The "|---|---|" row under a Markdown table header.
    private static final Pattern SEPARATOR_ROW = Pattern.compile("^[\\s|:+-]*-[\\s|:+-]*$");

    private final KnowledgeProperties properties;

    /** Lines of one document in reading order; blank lines are skipped for content but their numbers stay true. */
    public List<Chunk> chunk(List<TextLine> lines) {
        List<Chunk> chunks = new ArrayList<>();
        List<TextLine> pendingHeadings = new ArrayList<>();
        for (List<TextLine> section : sections(lines)) {
            if (section.stream().allMatch(line -> line.kind() == LineKind.HEADING)) {
                // A heading with no text of its own travels with the section that follows it.
                pendingHeadings.addAll(section);
                continue;
            }
            List<TextLine> body = new ArrayList<>(pendingHeadings);
            body.addAll(section);
            pendingHeadings.clear();
            cut(body, section.get(section.size() - 1).sectionPath(), chunks);
        }
        if (!pendingHeadings.isEmpty()) {
            cut(pendingHeadings, pendingHeadings.get(pendingHeadings.size() - 1).sectionPath(), chunks);
        }

        return chunks;
    }

    /** Runs of lines under one path; a heading always starts a new run. Blank lines are dropped, except in code. */
    private static List<List<TextLine>> sections(List<TextLine> lines) {
        List<List<TextLine>> sections = new ArrayList<>();
        List<TextLine> current = null;
        for (TextLine line : lines) {
            if (line.text().isBlank() && line.kind() != LineKind.CODE) {
                continue;
            }
            boolean starts = current == null || line.kind() == LineKind.HEADING
                || !current.get(0).sectionPath().equals(line.sectionPath());
            if (starts) {
                current = new ArrayList<>();
                sections.add(current);
            }
            current.add(line);
        }

        return sections;
    }

    private void cut(List<TextLine> section, List<String> path, List<Chunk> chunks) {
        Run run = new Run(properties.chunk(), path, chunks);
        int i = 0;
        while (i < section.size()) {
            LineKind kind = section.get(i).kind();
            int end = i + 1;
            if (kind == LineKind.TABLE_ROW || kind == LineKind.CODE) {
                while (end < section.size() && section.get(end).kind() == kind) {
                    end++;
                }
            }
            if (end - i == 1) {
                run.add(section.get(i));
            } else {
                run.addBlock(section.subList(i, end), kind);
            }
            i = end;
        }
        run.finish();
    }

    private static int tokens(List<TextLine> lines) {
        return lines.stream().mapToInt(line -> TokenEstimator.estimate(line.text())).sum();
    }

    /** The chunk being built. {@code fresh} counts lines not already part of the previous chunk (the overlap). */
    private static final class Run {
        private final KnowledgeProperties.Chunk limits;
        private final List<String> path;
        private final List<Chunk> chunks;
        private final List<TextLine> current = new ArrayList<>();
        private int tokens;
        private int fresh;

        Run(KnowledgeProperties.Chunk limits, List<String> path, List<Chunk> chunks) {
            this.limits = limits;
            this.path = path;
            this.chunks = chunks;
        }

        void add(TextLine line) {
            int lineTokens = TokenEstimator.estimate(line.text());
            if (lineTokens > limits.maxTokens()) {
                splitLongLine(line);
                return;
            }
            if (fresh > 0 && tokens + lineTokens > limits.maxTokens()) {
                close(true);
            }
            if (tokens + lineTokens > limits.maxTokens()) {
                // The overlap alone leaves no room for this line.
                current.clear();
                tokens = 0;
            }

            current.add(line);
            tokens += lineTokens;
            fresh++;
            if (tokens >= limits.targetTokens()) {
                close(true);
            }
        }

        /** A table or code block stays whole when it fits the maximum; otherwise it is split by rows or lines. */
        void addBlock(List<TextLine> block, LineKind kind) {
            int blockTokens = tokens(block);
            if (blockTokens > limits.maxTokens()) {
                close(false);
                if (kind == LineKind.TABLE_ROW) {
                    splitTable(block);
                } else {
                    splitByLines(block);
                }
                return;
            }
            if (fresh > 0 && tokens + blockTokens > limits.maxTokens()) {
                close(true);
            }
            if (tokens + blockTokens > limits.maxTokens()) {
                current.clear();
                tokens = 0;
            }
            current.addAll(block);
            tokens += blockTokens;
            fresh += block.size();
            if (tokens >= limits.targetTokens()) {
                close(true);
            }
        }

        void finish() {
            if (fresh > 0) {
                close(false);
            }
        }

        private void close(boolean keepOverlap) {
            if (fresh == 0) {
                current.clear();
                tokens = 0;
                return;
            }
            chunks.add(new Chunk(current.stream().map(TextLine::text).collect(Collectors.joining("\n")),
                current.get(0).page(), current.get(0).line(), current.get(current.size() - 1).line(), tokens, path));

            List<TextLine> overlap = keepOverlap ? overlapOfCurrent() : List.of();
            current.clear();
            current.addAll(overlap);
            tokens = tokens(overlap);
            fresh = 0;
        }

        /** The last whole prose lines that fit the overlap budget, in order; a table or code line is never repeated. */
        private List<TextLine> overlapOfCurrent() {
            int used = 0;
            int first = current.size();
            while (first > 0 && isProse(current.get(first - 1))) {
                int lineTokens = TokenEstimator.estimate(current.get(first - 1).text());
                if (used + lineTokens > limits.overlapTokens()) {
                    break;
                }
                used += lineTokens;
                first--;
            }

            return new ArrayList<>(current.subList(first, current.size()));
        }

        private static boolean isProse(TextLine line) {
            return line.kind() != LineKind.TABLE_ROW && line.kind() != LineKind.CODE;
        }

        /** Body rows in parts that fit the maximum, each led by the header row (and the separator row, if any). */
        private void splitTable(List<TextLine> table) {
            int headerRows = table.size() > 1 && SEPARATOR_ROW.matcher(table.get(1).text()).matches() ? 2 : 1;
            List<TextLine> header = table.subList(0, headerRows);
            int headerTokens = tokens(header);
            if (headerTokens * 2 > limits.maxTokens()) {
                // A header that large leaves no room for rows; cut by lines instead.
                splitByLines(table);
                return;
            }
            List<TextLine> part = new ArrayList<>(header);
            int partTokens = headerTokens;
            boolean hasBody = false;
            for (TextLine row : table.subList(headerRows, table.size())) {
                int rowTokens = TokenEstimator.estimate(row.text());
                if (hasBody && partTokens + rowTokens > limits.maxTokens()) {
                    addPart(part, partTokens, headerRows);
                    part = new ArrayList<>(header);
                    partTokens = headerTokens;
                    hasBody = false;
                }
                if (partTokens + rowTokens > limits.maxTokens()) {
                    // One row alone is too long even with the header: it is split like a long line.
                    splitLongLine(row);
                    continue;
                }
                part.add(row);
                partTokens += rowTokens;
                hasBody = true;
            }
            if (hasBody) {
                addPart(part, partTokens, headerRows);
            }
        }

        private void splitByLines(List<TextLine> lines) {
            List<TextLine> part = new ArrayList<>();
            int partTokens = 0;
            for (TextLine line : lines) {
                int lineTokens = TokenEstimator.estimate(line.text());
                if (lineTokens > limits.maxTokens()) {
                    if (!part.isEmpty()) {
                        addPart(part, partTokens, 0);
                        part = new ArrayList<>();
                        partTokens = 0;
                    }
                    splitLongLine(line);
                    continue;
                }
                if (!part.isEmpty() && partTokens + lineTokens > limits.maxTokens()) {
                    addPart(part, partTokens, 0);
                    part = new ArrayList<>();
                    partTokens = 0;
                }
                part.add(line);
                partTokens += lineTokens;
            }
            if (!part.isEmpty()) {
                addPart(part, partTokens, 0);
            }
        }

        /** The first {@code repeatedRows} rows of a part are a repeated header: the line range is that of the rest. */
        private void addPart(List<TextLine> part, int partTokens, int repeatedRows) {
            boolean ownRows = part.size() > repeatedRows;
            TextLine first = part.get(ownRows ? repeatedRows : 0);
            chunks.add(new Chunk(part.stream().map(TextLine::text).collect(Collectors.joining("\n")),
                first.page(), first.line(), part.get(part.size() - 1).line(), partTokens, path));
        }

        /** A line longer than the maximum becomes several chunks that all point at the same line. */
        private void splitLongLine(TextLine line) {
            close(false);
            int maxCharacters = limits.targetTokens() * 3;
            String text = line.text();
            StringBuilder piece = new StringBuilder();
            for (String word : text.split("\\s+")) {
                if (!piece.isEmpty() && piece.length() + 1 + word.length() > maxCharacters) {
                    addPiece(line, piece.toString());
                    piece.setLength(0);
                }
                for (String part : hardSplit(word, maxCharacters)) {
                    if (!piece.isEmpty() && piece.length() + 1 + part.length() > maxCharacters) {
                        addPiece(line, piece.toString());
                        piece.setLength(0);
                    }
                    if (!piece.isEmpty()) {
                        piece.append(' ');
                    }
                    piece.append(part);
                }
            }
            if (!piece.isEmpty()) {
                addPiece(line, piece.toString());
            }
        }

        private void addPiece(TextLine line, String piece) {
            chunks.add(new Chunk(piece, line.page(), line.line(), line.line(), TokenEstimator.estimate(piece), path));
        }

        /** A single word with no whitespace that is still too long is cut by characters. */
        private static List<String> hardSplit(String word, int maxCharacters) {
            List<String> parts = new ArrayList<>();
            for (int from = 0; from < word.length(); from += maxCharacters) {
                parts.add(word.substring(from, Math.min(from + maxCharacters, word.length())));
            }
            return parts;
        }
    }
}
