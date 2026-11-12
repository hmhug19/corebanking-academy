package vn.com.coreacademy.learning.service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.entity.LessonBlock;
import vn.com.coreacademy.learning.repository.LearningRepository;

/**
 * What the course generation job writes into its draft revision. The job runs in the background with nobody signed in,
 * so every call names the owner of the job (who was checked when the job started) and the audit trail shows them. Only
 * the draft the job created is written, lessons are found by position (the structure is locked while the job writes),
 * and a lesson the author edited is never overwritten.
 */
@Service
@RequiredArgsConstructor
public class LearningAiDraftService {
    /** What happened to a lesson the job tried to write. */
    public enum Written { WRITTEN, SKIPPED_USER_EDITED, MISSING, JOB_STOPPED }

    private final LearningRepository repository;
    private final LearningWriteLock writeLock;
    private final LearningContentService content;

    /**
     * Replaces the tree of the draft with the outline: chapters and lessons without content, all {@code pending}. The
     * title, programme, level and any summary or objectives the author already wrote stay; the AI ones fill only what
     * is empty (the author owns the description).
     * <p>
     * {@code stillWanted} is asked inside the transaction once the course is locked, so a stop or cancel that landed
     * while the model was working cannot be overtaken by the write. Nothing is written, and false returned, when it
     * says no, or when a lesson of the draft is already {@code user_edited} or {@code written}: a repeated run of the
     * outline step must not replace the author's work or reset their lessons to {@code pending}.
     *
     * @return true when the outline was written
     */
    @Transactional
    public boolean writeOutline(Long courseId, Long revisionId, String owner, String summaryWhenEmpty,
                                List<String> objectivesWhenEmpty, List<LearningRequests.ChapterInput> chapters,
                                BooleanSupplier stillWanted) {
        Course course = lockedCourse(courseId);
        if (!stillWanted.getAsBoolean()) {
            return false;
        }
        CourseRevision revision = repository.require(CourseRevision.class, revisionId);
        content.requireDraft(course, revision);
        if (repository.lessons(revisionId).stream().anyMatch(lesson ->
            LearningAiOwnership.USER_EDITED.equals(lesson.getAiState())
                || LearningAiOwnership.WRITTEN.equals(lesson.getAiState()))) {
            return false;
        }
        var rule = repository.rules(revisionId).get(0);
        List<String> objectives = repository.objectives(revisionId).stream().map(row -> row.getDescription())
            .toList();
        String summary = revision.getSummary() == null || revision.getSummary().isBlank() ? summaryWhenEmpty
            : revision.getSummary();
        content.applyDraft(course, revision, new LearningRequests.DraftContent(revision.getProgramId(),
            revision.getModuleId(), revision.getTitle(), summary, revision.getLevel(), revision.getDefaultRequirement(),
            revision.getCoverAssetId(), revision.getCoverIcon(), revision.getCoverColor(),
            objectives.isEmpty() ? objectivesWhenEmpty : objectives, chapters,
            rule.getRewardPoints() == null ? 0 : rule.getRewardPoints(), rule.getFinalQuizId()), owner);
        repository.flush();
        for (CourseLesson lesson : repository.lessons(revisionId)) {
            lesson.setAiState(LearningAiOwnership.PENDING);
            repository.save(lesson, owner);
        }
        return true;
    }

    /**
     * Writes the blocks of the lesson at the position if the job still owns it ({@code pending} or {@code writing}),
     * and marks it {@code written}. {@code stillWanted} is asked inside the transaction once the course is locked; when
     * it says no (the job was stopped or cancelled) nothing is written and {@code JOB_STOPPED} is returned.
     */
    @Transactional
    public Written writeLesson(Long courseId, Long revisionId, int chapter, int lesson,
                               List<LearningRequests.BlockInput> blocks, String owner, BooleanSupplier stillWanted) {
        Course course = lockedCourse(courseId);
        if (!stillWanted.getAsBoolean()) {
            return Written.JOB_STOPPED;
        }
        CourseRevision revision = repository.require(CourseRevision.class, revisionId);
        content.requireDraft(course, revision);
        CourseLesson target = lessonAt(revisionId, chapter, lesson);
        if (target == null) {
            return Written.MISSING;
        }
        if (!LearningAiOwnership.PENDING.equals(target.getAiState())
            && !LearningAiOwnership.WRITING.equals(target.getAiState())) {
            return Written.SKIPPED_USER_EDITED;
        }
        repository.steps(revisionId).stream().filter(step -> blockIdsOf(revisionId, target.getId())
            .contains(step.getBlockId())).forEach(repository::remove);
        repository.assetLinks(revisionId).stream().filter(link -> blockIdsOf(revisionId, target.getId())
            .contains(link.getBlockId())).forEach(repository::remove);
        repository.blocks(revisionId).stream().filter(block -> block.getLessonId().equals(target.getId()))
            .forEach(repository::remove);
        repository.flush();
        for (int i = 0; i < blocks.size(); i++) {
            content.writeBlock(target.getId(), blocks.get(i), i, owner);
        }
        target.setAiState(LearningAiOwnership.WRITTEN);
        repository.save(target, owner);
        return Written.WRITTEN;
    }

    /**
     * The job starts a lesson: true and {@code writing} when the job still owns it ({@code pending} or
     * {@code writing}); false when the author edited it meanwhile, so the model is not asked for nothing.
     */
    @Transactional
    public boolean startLesson(Long courseId, Long revisionId, int chapter, int lesson, String owner) {
        lockedCourse(courseId);
        CourseLesson target = lessonAt(revisionId, chapter, lesson);
        if (target == null || (!LearningAiOwnership.PENDING.equals(target.getAiState())
            && !LearningAiOwnership.WRITING.equals(target.getAiState()))) {
            return false;
        }
        target.setAiState(LearningAiOwnership.WRITING);
        repository.save(target, owner);
        return true;
    }

    /** Sets the AI state of one lesson (the job starts it, or the author asks for it again). */
    @Transactional
    public boolean setLessonState(Long courseId, Long revisionId, int chapter, int lesson, String state,
                                  String owner) {
        lockedCourse(courseId);
        CourseLesson target = lessonAt(revisionId, chapter, lesson);
        if (target == null) {
            return false;
        }
        target.setAiState(state);
        repository.save(target, owner);
        return true;
    }

    /** A lesson the job was writing when it stopped goes back to {@code pending}; written and edited ones stay. */
    @Transactional
    public int revertWriting(Long courseId, Long revisionId, String owner) {
        lockedCourse(courseId);
        int reverted = 0;
        for (CourseLesson lesson : repository.lessons(revisionId)) {
            if (LearningAiOwnership.WRITING.equals(lesson.getAiState())) {
                lesson.setAiState(LearningAiOwnership.PENDING);
                repository.save(lesson, owner);
                reverted++;
            }
        }
        return reverted;
    }

    /** Fills the summary of the draft only when the author left it empty (they own the description). */
    @Transactional
    public boolean fillSummaryIfEmpty(Long courseId, Long revisionId, String summary, String owner) {
        lockedCourse(courseId);
        CourseRevision revision = repository.require(CourseRevision.class, revisionId);
        if (revision.getSummary() != null && !revision.getSummary().isBlank()) {
            return false;
        }
        revision.setSummary(summary);
        repository.save(revision, owner);
        return true;
    }

    /** The titles and states of the lessons of the draft in order, per chapter: what the job and the author made. */
    @Transactional(readOnly = true)
    public List<ChapterState> structure(Long revisionId) {
        List<CourseLesson> lessons = repository.lessons(revisionId);
        List<ChapterState> result = new ArrayList<>();
        for (CourseChapter chapter : repository.chapters(revisionId)) {
            List<LessonState> row = new ArrayList<>();
            for (CourseLesson lesson : lessons) {
                if (lesson.getChapterId().equals(chapter.getId())) {
                    row.add(new LessonState(lesson.getTitle(), lesson.getAiState(),
                        lesson.getEstimatedDurationSeconds() == null ? 0 : lesson.getEstimatedDurationSeconds().longValue()));
                }
            }
            result.add(new ChapterState(chapter.getTitle(), row));
        }
        return result;
    }

    public record LessonState(String title, String aiState, long estimatedSeconds) {
    }

    public record ChapterState(String title, List<LessonState> lessons) {
    }

    private Course lockedCourse(Long courseId) {
        writeLock.acquire();
        Course course = repository.require(Course.class, courseId);
        repository.refresh(course);
        return course;
    }

    private CourseLesson lessonAt(Long revisionId, int chapter, int lesson) {
        List<CourseChapter> chapters = repository.chapters(revisionId);
        if (chapter < 0 || chapter >= chapters.size()) {
            return null;
        }
        Long chapterId = chapters.get(chapter).getId();
        List<CourseLesson> inChapter = repository.lessons(revisionId).stream()
            .filter(row -> row.getChapterId().equals(chapterId)).toList();
        return lesson < 0 || lesson >= inChapter.size() ? null : inChapter.get(lesson);
    }

    private List<Long> blockIdsOf(Long revisionId, Long lessonId) {
        return repository.blocks(revisionId).stream().filter(block -> block.getLessonId().equals(lessonId))
            .map(LessonBlock::getId).toList();
    }
}
