package vn.com.coreacademy.assistant.drafting;

import java.util.List;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.BlockSnapshot;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftSnapshot;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.LessonSnapshot;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The step 4 publish blockers are the editor's pre-publish check ({@code draftChecks} and {@code lessonProblem} in the
 * page's {@code course-draft.ts}) run on the saved draft. The labels below are copied from the page; the page's
 * {@code course-draft.spec.ts} pins the same list, so changing one side fails a test on the other.
 */
class DraftingPublishCheckTest {
    /** The page's required check labels, in its order (the recommended ones never block). */
    private static final List<String> PAGE_REQUIRED_LABELS = List.of(
        "Có tên khóa học và chương trình",
        "Có ít nhất 1 bài học",
        "Mọi bài học có nội dung, đủ tệp hoặc đã chọn bài kiểm tra",
        "Bài kiểm tra cuối khóa đã xuất bản",
        "Đã chọn mức độ khóa học");

    private static LessonSnapshot content(BlockSnapshot... blocks) {
        return new LessonSnapshot("content", null, null, List.of(blocks));
    }

    private static BlockSnapshot text(String body) {
        return new BlockSnapshot("text", body, 0, 0);
    }

    private static DraftSnapshot draft(String title, Long programId, String level, List<LessonSnapshot> lessons,
                                       Long finalQuizId) {
        return new DraftSnapshot(title, programId, level, true, lessons, finalQuizId);
    }

    @Test
    void theLabelsAreThePagesOwn() {
        assertThat(List.of(DraftingPublishCheck.NAME_AND_PROGRAMME, DraftingPublishCheck.A_LESSON,
            DraftingPublishCheck.LESSONS_COMPLETE, DraftingPublishCheck.FINAL_EXAM_PUBLISHED,
            DraftingPublishCheck.LEVEL)).containsExactlyElementsOf(PAGE_REQUIRED_LABELS);
    }

    @Test
    void aDraftOfEmptyLessonsFromTheFlowIsBlockedByItsLessonsAndCountsThem() {
        DraftSnapshot outline = draft("Mở tài khoản", 3L, "basic", List.of(content(), content(),
            content(text("Nội dung"))), null);

        assertThat(DraftingPublishCheck.blockers(outline, null)).containsExactly(
            DraftingPublishCheck.LESSONS_COMPLETE, "2 bài chưa có nội dung");
    }

    @Test
    void everyRequiredCheckThatFailsIsListedInThePagesOrder() {
        DraftSnapshot empty = draft(" ", null, null, List.of(), 9L);

        assertThat(DraftingPublishCheck.blockers(empty, "CLOSED")).containsExactly(
            DraftingPublishCheck.NAME_AND_PROGRAMME, DraftingPublishCheck.A_LESSON,
            DraftingPublishCheck.FINAL_EXAM_PUBLISHED, DraftingPublishCheck.LEVEL);
        // a missing or deleted exam is not published either
        assertThat(DraftingPublishCheck.blockers(empty, null)).contains(DraftingPublishCheck.FINAL_EXAM_PUBLISHED);
    }

    @Test
    void aCompleteDraftWithADraftOrPublishedExamHasNoBlockersAndObjectivesNeverBlock() {
        DraftSnapshot ready = new DraftSnapshot("Mở tài khoản", 3L, "basic", false,
            List.of(content(text("Nội dung"))), 9L);

        // the step 3 exam is a draft: it is published with the course
        assertThat(DraftingPublishCheck.blockers(ready, "DRAFT")).isEmpty();
        assertThat(DraftingPublishCheck.blockers(ready, "PUBLISHED")).isEmpty();
        assertThat(DraftingPublishCheck.blockers(new DraftSnapshot("Mở tài khoản", 3L, "basic", false,
            List.of(content(text("Nội dung"))), null), null)).isEmpty();
    }

    @Test
    void aLessonHasTheSameProblemsAsOnThePage() {
        assertThat(DraftingPublishCheck.problem(content())).isEqualTo("Thiếu nội dung");
        assertThat(DraftingPublishCheck.problem(content(text("  ")))).isEqualTo("Khối văn bản chưa có nội dung");
        assertThat(DraftingPublishCheck.problem(content(new BlockSnapshot("process", null, 0, 0))))
            .isEqualTo("Khối quy trình chưa có bước");
        assertThat(DraftingPublishCheck.problem(content(new BlockSnapshot("video", null, 0, 0))))
            .isEqualTo("Thiếu tệp");
        assertThat(DraftingPublishCheck.problem(content(new BlockSnapshot("attachment", null, 0, 0)))).isNull();
        assertThat(DraftingPublishCheck.problem(content(new BlockSnapshot("pdf", null, 0, 1)))).isNull();
        assertThat(DraftingPublishCheck.problem(new LessonSnapshot("quiz", null, null, List.of())))
            .isEqualTo("Chưa chọn bài kiểm tra");
        assertThat(DraftingPublishCheck.problem(new LessonSnapshot("quiz", 4L, null, List.of()))).isNull();
        assertThat(DraftingPublishCheck.problem(new LessonSnapshot("scorm", null, null, List.of())))
            .isEqualTo("Chưa chọn gói SCORM");
    }
}
