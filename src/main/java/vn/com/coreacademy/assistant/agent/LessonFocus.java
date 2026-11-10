package vn.com.coreacademy.assistant.agent;

/** A lesson the user may read, as the turn sees it: ids for retrieval and titles for the conversation label. */
public record LessonFocus(long courseId, long revisionId, long lessonId, String courseTitle, String chapterTitle,
                          String lessonTitle) {
    /** "Khóa › Bài", the label shown on a conversation that was opened from the lesson. */
    public String label() {
        String text = courseTitle + " › " + lessonTitle;

        return text.length() <= 255 ? text : text.substring(0, 255);
    }
}
