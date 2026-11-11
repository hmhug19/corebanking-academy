package vn.com.coreacademy.assistant.agent;

import vn.com.coreacademy.assistant.dto.LessonContext;

/** Turns the lesson a client names into one the signed-in user may read; the check runs on the request thread. */
public interface LessonAccess {
    /** Throws {@code ASSISTANT-LESSON_NOT_FOUND} when the lesson is missing, not published or not readable. */
    LessonFocus require(LessonContext context);
}
