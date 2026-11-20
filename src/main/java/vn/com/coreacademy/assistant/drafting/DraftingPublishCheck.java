package vn.com.coreacademy.assistant.drafting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.BlockSnapshot;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftSnapshot;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.LessonSnapshot;
import vn.com.coreacademy.learning.constants.LearningCodes.BlockType;
import vn.com.coreacademy.learning.constants.LearningCodes.LessonType;
import vn.com.coreacademy.learning.service.LearningQuizDirectory;
import vn.com.coreacademy.learning.service.LearningQuizDirectory.QuizRef;

/**
 * What still blocks publishing a drafted course, for the step 4 chat summary. It is the editor's own pre-publish check
 * ("Kiểm tra trước khi xuất bản", {@code draftChecks} in the page's {@code course-draft.ts}) run on the saved draft:
 * the same rules and the same labels, so the chat and the page say the same thing. Only the required checks that fail
 * are listed (the recommended ones never block), and failing lessons are also counted ("3 bài chưa có nội dung"). The
 * server checks again when the author publishes; this list only informs.
 */
@Component
@RequiredArgsConstructor
public class DraftingPublishCheck {
    // The labels of the page's draftChecks; DraftingPublishCheckTest and the page's course-draft.spec pin both copies.
    static final String NAME_AND_PROGRAMME = "Có tên khóa học và chương trình";
    static final String A_LESSON = "Có ít nhất 1 bài học";
    static final String LESSONS_COMPLETE = "Mọi bài học có nội dung, đủ tệp hoặc đã chọn bài kiểm tra";
    static final String FINAL_EXAM_PUBLISHED = "Bài kiểm tra cuối khóa đã xuất bản";
    static final String LEVEL = "Đã chọn mức độ khóa học";
    /** The block types that hold a file (the page's FILE_USAGE); an attachment block may have none. */
    private static final Set<String> FILE_BLOCKS = Set.of(BlockType.VIDEO, BlockType.PDF, BlockType.IMAGE);

    private final CourseDraftingRepository drafting;
    private final LearningQuizDirectory quizzes;

    /** The blockers of the saved draft revision; a revision that is gone has nothing to publish. */
    public List<String> blockers(long revisionId) {
        DraftSnapshot draft = drafting.draftSnapshot(revisionId).orElse(null);
        if (draft == null) {
            return List.of(A_LESSON);
        }
        Optional<QuizRef> exam = draft.finalQuizId() == null ? Optional.empty() : quizzes.find(draft.finalQuizId())
            .filter(quiz -> !quiz.deleted());

        return blockers(draft, exam.map(QuizRef::status).orElse(null));
    }

    /**
     * The required checks that fail, in the page's order. {@code examStatus} is the selected final exam's status, null
     * when it is missing or deleted: a draft exam is published with the course, a published one is fine.
     */
    static List<String> blockers(DraftSnapshot draft, String examStatus) {
        List<String> blockers = new ArrayList<>();
        if (draft.title() == null || draft.title().isBlank() || draft.programId() == null) {
            blockers.add(NAME_AND_PROGRAMME);
        }
        if (draft.lessons().isEmpty()) {
            blockers.add(A_LESSON);
        }
        long unfinished = draft.lessons().stream().filter(lesson -> problem(lesson) != null).count();
        if (unfinished > 0) {
            blockers.add(LESSONS_COMPLETE);
            blockers.add(unfinished + " bài chưa có nội dung");
        }
        if (draft.finalQuizId() != null && !"DRAFT".equals(examStatus) && !"PUBLISHED".equals(examStatus)) {
            blockers.add(FINAL_EXAM_PUBLISHED);
        }
        if (draft.level() == null || draft.level().isBlank()) {
            blockers.add(LEVEL);
        }

        return List.copyOf(blockers);
    }

    /** The page's lessonProblem: a content lesson needs a block and its files; a quiz or SCORM lesson its package. */
    static String problem(LessonSnapshot lesson) {
        if (LessonType.SCORM.equals(lesson.lessonType())) {
            return lesson.scormPackageId() == null ? "Chưa chọn gói SCORM" : null;
        }
        if (LessonType.QUIZ.equals(lesson.lessonType())) {
            return lesson.quizId() == null ? "Chưa chọn bài kiểm tra" : null;
        }
        List<BlockSnapshot> blocks = lesson.blocks();
        if (blocks.isEmpty()) {
            return "Thiếu nội dung";
        }
        if (blocks.stream().anyMatch(block -> BlockType.TEXT.equals(block.blockType())
            && (block.body() == null || block.body().isBlank()))) {
            return "Khối văn bản chưa có nội dung";
        }
        if (blocks.stream().anyMatch(block -> BlockType.PROCESS.equals(block.blockType()) && block.steps() == 0)) {
            return "Khối quy trình chưa có bước";
        }
        if (blocks.stream().anyMatch(block -> BlockType.FLOW.equals(block.blockType()) && block.steps() == 0)) {
            return "Khối mô phỏng luồng chưa có bước chuyển";
        }
        return blocks.stream().anyMatch(block -> FILE_BLOCKS.contains(block.blockType()) && block.assets() == 0)
            ? "Thiếu tệp" : null;
    }
}
