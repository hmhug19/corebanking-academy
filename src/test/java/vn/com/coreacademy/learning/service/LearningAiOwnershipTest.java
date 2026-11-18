package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.dto.LearningRequests.BlockInput;
import vn.com.coreacademy.learning.dto.LearningRequests.ChapterInput;
import vn.com.coreacademy.learning.dto.LearningRequests.DraftContent;
import vn.com.coreacademy.learning.dto.LearningRequests.LessonInput;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.LessonBlock;
import vn.com.coreacademy.learning.repository.LearningRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who owns a lesson of an AI course after a save, and what the locked structure refuses. */
class LearningAiOwnershipTest {
    private static final long REVISION = 9L;

    private final LearningRepository repository = mock(LearningRepository.class);
    private boolean locked;
    private final LearningAiOwnership ownership = new LearningAiOwnership(repository, List.of(id -> locked));

    private static CourseChapter chapter(long id, String title) {
        CourseChapter chapter = new CourseChapter();
        chapter.setId(id);
        chapter.setTitle(title);
        return chapter;
    }

    private static CourseLesson lesson(long id, long chapterId, String title, String state) {
        CourseLesson lesson = new CourseLesson();
        lesson.setId(id);
        lesson.setChapterId(chapterId);
        lesson.setTitle(title);
        lesson.setAiState(state);
        return lesson;
    }

    private static LessonBlock block(long lessonId, String body) {
        LessonBlock block = new LessonBlock();
        block.setId(lessonId * 10);
        block.setLessonId(lessonId);
        block.setBlockType("text");
        block.setTitle("Nội dung");
        block.setBody(body);
        block.setSortNo(0);
        return block;
    }

    private static BlockInput input(String body) {
        return new BlockInput("text", "Nội dung", body, "markdown", "manual", null, true, List.of(), List.of(), null,
            null, false);
    }

    private static LessonInput lessonInput(String title, BlockInput... blocks) {
        return new LessonInput(title, "content", BigDecimal.valueOf(600), true, null, List.of(blocks));
    }

    private static DraftContent content(ChapterInput... chapters) {
        return new DraftContent(1L, null, "Khóa", "", "basic", "mandatory", null, null, null, List.of(),
            List.of(chapters), 0);
    }

    private void course(CourseLesson... lessons) {
        when(repository.chapters(REVISION)).thenReturn(List.of(chapter(1, "Chương 1")));
        when(repository.lessons(REVISION)).thenReturn(List.of(lessons));
        when(repository.blocks(REVISION)).thenReturn(List.of(block(11, "Nội dung AI viết"), block(12, "Nội dung AI viết")));
        when(repository.steps(REVISION)).thenReturn(List.of());
    }

    @Test
    void aCourseWithoutAiLessonsIsNotTouched() {
        course(lesson(11, 1, "Bài 1", null));

        assertThat(ownership.snapshot(REVISION)).isNull();
    }

    @Test
    void whileLockedAddingARemovingOrReorderingALessonIsRefused() {
        course(lesson(11, 1, "Bài 1", "written"), lesson(12, 1, "Bài 2", "pending"));
        var before = ownership.snapshot(REVISION);
        locked = true;

        assertThatThrownBy(() -> ownership.requireStructureAllowed(REVISION, before, content(new ChapterInput(
            "Chương 1", List.of(lessonInput("Bài 1"), lessonInput("Bài 2"), lessonInput("Bài 3"))))))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ownership.requireStructureAllowed(REVISION, before, content(new ChapterInput(
            "Chương 1", List.of(lessonInput("Bài 1"))))))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ownership.requireStructureAllowed(REVISION, before, content(new ChapterInput(
            "Chương 1", List.of(lessonInput("Bài 2"), lessonInput("Bài 1"))))))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void whileLockedARenameAndAnEditOfContentAreAllowed() {
        course(lesson(11, 1, "Bài 1", "written"), lesson(12, 1, "Bài 2", "pending"));
        var before = ownership.snapshot(REVISION);
        locked = true;

        ownership.requireStructureAllowed(REVISION, before, content(new ChapterInput("Chương một",
            List.of(lessonInput("Bài một", input("Tôi sửa")), lessonInput("Bài 2")))));
    }

    @Test
    void afterTheJobEndsTheStructureMayChange() {
        course(lesson(11, 1, "Bài 1", "written"));
        var before = ownership.snapshot(REVISION);
        locked = false;

        ownership.requireStructureAllowed(REVISION, before, content(new ChapterInput("Chương 1",
            List.of(lessonInput("Bài 1"), lessonInput("Bài mới")))));
    }

    @Test
    void aSaveThatChangesAWrittenLessonMakesItUserEditedAndAnUnchangedOneStaysWritten() {
        course(lesson(11, 1, "Bài 1", "written"), lesson(12, 1, "Bài 2", "written"));
        var before = ownership.snapshot(REVISION);
        // after the save the tree is rewritten with new ids but the same order
        CourseLesson one = lesson(21, 1, "Bài 1", null);
        CourseLesson two = lesson(22, 1, "Bài 2", null);
        when(repository.lessons(REVISION)).thenReturn(List.of(one, two));
        DraftContent saved = content(new ChapterInput("Chương 1", List.of(lessonInput("Bài 1", input("Đã sửa")),
            lessonInput("Bài 2", input("Nội dung AI viết")))));

        ownership.reconcile(REVISION, before, saved);

        assertThat(one.getAiState()).isEqualTo("user_edited");
        assertThat(two.getAiState()).isEqualTo("written");
    }

    @Test
    void aLessonTheAuthorEditedStaysTheirsAndANewEmptyLessonIsPending() {
        course(lesson(11, 1, "Bài 1", "user_edited"));
        var before = ownership.snapshot(REVISION);
        CourseLesson one = lesson(21, 1, "Bài 1", null);
        CourseLesson added = lesson(22, 1, "Bài mới", null);
        when(repository.lessons(REVISION)).thenReturn(List.of(one, added));
        DraftContent saved = content(new ChapterInput("Chương 1", List.of(lessonInput("Bài 1", input("Nội dung AI viết")),
            lessonInput("Bài mới"))));

        ownership.reconcile(REVISION, before, saved);

        assertThat(one.getAiState()).isEqualTo("user_edited");
        assertThat(added.getAiState()).isEqualTo("pending");
    }
}
