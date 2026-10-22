package vn.com.coreacademy.knowledge.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.extraction.TextLine;

/**
 * Cuts text at line boundaries so a chunk always knows its page and line range. The same algorithm, with smaller
 * limits, runs in section 7 of docs/architecture/ai-assistant-rag.html; keep the two in step.
 */
@Component
@RequiredArgsConstructor
public class Chunker {
    private final KnowledgeProperties properties;

    /** Lines of one document in reading order; blank lines are skipped for content but their numbers stay true. */
    public List<Chunk> chunk(List<TextLine> lines) {
        Run run = new Run(properties.chunk());
        for (TextLine line : lines) {
            if (!line.text().isBlank()) {
                run.add(line);
            }
        }
        run.finish();

        return run.chunks;
    }

    /** The chunk being built. {@code fresh} counts lines not already part of the previous chunk (the overlap). */
    private static final class Run {
        private final KnowledgeProperties.Chunk limits;
        private final List<Chunk> chunks = new ArrayList<>();
        private final List<TextLine> current = new ArrayList<>();
        private int tokens;
        private int fresh;

        Run(KnowledgeProperties.Chunk limits) {
            this.limits = limits;
        }

        void add(TextLine line) {
            int lineTokens = TokenEstimator.estimate(line.text());
            if (!current.isEmpty() && !Objects.equals(current.get(0).page(), line.page())) {
                close(false);
            }
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
                current.get(0).page(), current.get(0).line(), current.get(current.size() - 1).line(), tokens));

            List<TextLine> overlap = keepOverlap ? overlapOfCurrent() : List.of();
            int overlapTokens = overlap.stream().mapToInt(line -> TokenEstimator.estimate(line.text())).sum();
            current.clear();
            current.addAll(overlap);
            tokens = overlapTokens;
            fresh = 0;
        }

        /** The last whole lines that fit the overlap budget, in order. */
        private List<TextLine> overlapOfCurrent() {
            int used = 0;
            int first = current.size();
            while (first > 0) {
                int lineTokens = TokenEstimator.estimate(current.get(first - 1).text());
                if (used + lineTokens > limits.overlapTokens()) {
                    break;
                }
                used += lineTokens;
                first--;
            }

            return new ArrayList<>(current.subList(first, current.size()));
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
            chunks.add(new Chunk(piece, line.page(), line.line(), line.line(), TokenEstimator.estimate(piece)));
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
