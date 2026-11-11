package vn.com.coreacademy.assistant.course;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.learning.dto.LearningRequests.ChapterInput;
import vn.com.coreacademy.learning.dto.LearningRequests.LessonInput;

/** The outline the model made, as the chapters and empty lessons of the draft course (and the keys of its lessons). */
final class CourseOutlineMapper {
    private static final Pattern KEY = Pattern.compile("c(\\d+)l(\\d+)");

    private CourseOutlineMapper() {
    }

    static List<ChapterInput> chapters(JsonNode outline) {
        List<ChapterInput> chapters = new ArrayList<>();
        for (JsonNode chapter : outline.path("chapters")) {
            List<LessonInput> lessons = new ArrayList<>();
            for (JsonNode lesson : chapter.path("lessons")) {
                lessons.add(new LessonInput(lesson.path("title").asText(), "content",
                    BigDecimal.valueOf(lesson.path("estimatedMinutes").asInt(10) * 60L), true, null, List.of()));
            }
            chapters.add(new ChapterInput(chapter.path("title").asText(), lessons));
        }

        return chapters;
    }

    static List<String> objectives(JsonNode outline) {
        List<String> objectives = new ArrayList<>();
        outline.path("objectives").forEach(objective -> objectives.add(objective.asText()));

        return objectives;
    }

    /** Zero based {chapter, lesson} of a key like c2l3, or null when it is not one. */
    static int[] position(String key) {
        Matcher match = KEY.matcher(key);
        if (!match.matches()) {
            return null;
        }

        return new int[] {Integer.parseInt(match.group(1)) - 1, Integer.parseInt(match.group(2)) - 1};
    }
}
