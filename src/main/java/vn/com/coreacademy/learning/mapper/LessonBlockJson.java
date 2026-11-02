package vn.com.coreacademy.learning.mapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import vn.com.coreacademy.learning.dto.LearningRequests.TranscriptLineInput;
import vn.com.coreacademy.learning.dto.LearningRequests.VideoChapterInput;
import vn.com.coreacademy.learning.dto.LearningViews.TranscriptLineView;
import vn.com.coreacademy.learning.dto.LearningViews.VideoChapterView;

/**
 * Chuyển mốc video và transcript giữa cột jsonb của lesson_blocks và danh sách DTO. Cột NULL đọc ra danh sách rỗng;
 * danh sách rỗng hoặc null được lưu thành NULL.
 */
public final class LessonBlockJson {
    private static final String START = "startSeconds";
    private static final String TITLE = "title";
    private static final String TEXT = "text";

    private LessonBlockJson() {
    }

    public static List<VideoChapterView> chapters(JsonNode column) {
        return read(column, TITLE, VideoChapterView::new);
    }

    public static List<TranscriptLineView> lines(JsonNode column) {
        return read(column, TEXT, TranscriptLineView::new);
    }

    public static JsonNode chaptersColumn(List<VideoChapterInput> chapters) {
        return write(chapters == null ? List.of() : chapters, TITLE, VideoChapterInput::startSeconds,
            VideoChapterInput::title);
    }

    public static JsonNode linesColumn(List<TranscriptLineInput> lines) {
        return write(lines == null ? List.of() : lines, TEXT, TranscriptLineInput::startSeconds,
            TranscriptLineInput::text);
    }

    private static <T> List<T> read(JsonNode column, String label, BiFunction<BigDecimal, String, T> factory) {
        List<T> items = new ArrayList<>();
        if (column != null && column.isArray()) {
            for (JsonNode item : column) {
                items.add(factory.apply(item.path(START).decimalValue(), item.path(label).asText()));
            }
        }
        return items;
    }

    private static <T> JsonNode write(List<T> items, String label, Function<T, BigDecimal> start,
                                      Function<T, String> text) {
        if (items.isEmpty()) {
            return null;
        }
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        for (T item : items) {
            array.addObject().put(START, start.apply(item)).put(label, text.apply(item).strip());
        }
        return array;
    }
}
