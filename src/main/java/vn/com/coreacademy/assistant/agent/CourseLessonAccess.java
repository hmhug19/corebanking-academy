package vn.com.coreacademy.assistant.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.dto.LessonContext;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.index.CourseContentRetrieval;

/** One answer for a lesson that does not exist and one the user may not read, so an id reveals nothing. */
@Component
@RequiredArgsConstructor
public class CourseLessonAccess implements LessonAccess {
    private final CourseContentRetrieval courses;

    @Override
    public LessonFocus require(LessonContext context) {
        if (context.courseId() == null || context.revisionId() == null || context.lessonId() == null) {
            throw BusinessException.validation("context", "REQUIRED", "Thiếu mã khóa học, revision hoặc bài học.");
        }

        return courses.lesson(context.courseId(), context.revisionId(), context.lessonId())
            .map(lesson -> new LessonFocus(lesson.courseId(), lesson.revisionId(), lesson.lessonId(),
                lesson.courseTitle(), lesson.chapterTitle(), lesson.lessonTitle()))
            .orElseThrow(() -> new BusinessException(AssistantResponseCode.LESSON_NOT_FOUND));
    }
}
