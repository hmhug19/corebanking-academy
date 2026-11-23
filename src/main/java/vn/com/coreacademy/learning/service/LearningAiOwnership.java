package vn.com.coreacademy.learning.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.LessonBlock;
import vn.com.coreacademy.learning.entity.ProcessStep;
import vn.com.coreacademy.learning.mapper.FlowModelJson;
import vn.com.coreacademy.learning.repository.LearningRepository;

/**
 * Who owns a lesson of an AI generated course: the generation job (state {@code pending}, {@code writing},
 * {@code written}) or the author ({@code user_edited}). A user save replaces the whole draft tree, so the state is kept
 * by position: a lesson whose content differs from what the job wrote becomes {@code user_edited}, and the job never
 * writes such a lesson again. While the structure is locked, a save that adds, removes or reorders chapters or
 * lessons is refused. A course without AI managed lessons is not touched.
 */
@Component
@RequiredArgsConstructor
public class LearningAiOwnership {
    public static final String PENDING = "pending";
    public static final String WRITING = "writing";
    public static final String WRITTEN = "written";
    public static final String USER_EDITED = "user_edited";

    private final LearningRepository repository;
    private final List<CourseStructureGuard> guards;

    /** One lesson as it was before the save. */
    record Before(String title, String state, String fingerprint) {
    }

    /** The AI managed lessons of the revision as they were before a save. */
    record Snapshot(List<String> chapterTitles, List<List<Before>> lessons) {
    }

    /** The AI managed lessons of the revision, or null when it has none. */
    Snapshot snapshot(Long revisionId) {
        List<CourseLesson> lessons = repository.lessons(revisionId);
        if (lessons.stream().noneMatch(lesson -> lesson.getAiState() != null)) {
            return null;
        }
        Map<Long, List<LessonBlock>> blocks = new HashMap<>();
        repository.blocks(revisionId).forEach(block -> blocks.computeIfAbsent(block.getLessonId(),
            key -> new ArrayList<>()).add(block));
        Map<Long, List<ProcessStep>> steps = new HashMap<>();
        repository.steps(revisionId).forEach(step -> steps.computeIfAbsent(step.getBlockId(),
            key -> new ArrayList<>()).add(step));
        List<String> titles = new ArrayList<>();
        List<List<Before>> result = new ArrayList<>();
        for (CourseChapter chapter : repository.chapters(revisionId)) {
            titles.add(chapter.getTitle());
            List<Before> row = new ArrayList<>();
            for (CourseLesson lesson : lessons) {
                if (lesson.getChapterId().equals(chapter.getId())) {
                    row.add(new Before(lesson.getTitle(), lesson.getAiState(),
                        fingerprint(blocks.getOrDefault(lesson.getId(), List.of()), steps)));
                }
            }
            result.add(row);
        }
        return new Snapshot(titles, result);
    }

    /** Throws when the structure is locked and the new content adds, removes or reorders chapters or lessons. */
    void requireStructureAllowed(Long revisionId, Snapshot before, LearningRequests.DraftContent content) {
        if (guards.stream().noneMatch(guard -> guard.structureLocked(revisionId))) {
            return;
        }
        List<LearningRequests.ChapterInput> chapters = content.chapters();
        boolean same = chapters.size() == before.lessons().size();
        for (int i = 0; same && i < chapters.size(); i++) {
            same = chapters.get(i).lessons().size() == before.lessons().get(i).size();
        }
        if (same && (reordered(before.chapterTitles(),
            chapters.stream().map(LearningRequests.ChapterInput::title).toList())
            || reorderedLessons(before.lessons(), chapters))) {
            same = false;
        }
        if (!same) {
            throw BusinessException.validation("chapters", "STRUCTURE_LOCKED",
                "Dàn ý đã được duyệt và AI đang viết bài nên chưa thêm, xóa hoặc đổi thứ tự chương, bài được. "
                    + "Hãy dừng việc tạo bằng AI hoặc chờ xong.");
        }
    }

    private static boolean reorderedLessons(List<List<Before>> before, List<LearningRequests.ChapterInput> chapters) {
        for (int i = 0; i < chapters.size(); i++) {
            List<String> old = before.get(i).stream().map(Before::title).toList();
            List<String> now = chapters.get(i).lessons().stream().map(LearningRequests.LessonInput::title).toList();
            if (reordered(old, now)) {
                return true;
            }
        }
        return false;
    }

    /** The same titles in another order; a single rename is not a reorder. */
    private static boolean reordered(List<String> old, List<String> now) {
        if (old.equals(now)) {
            return false;
        }
        List<String> a = new ArrayList<>(old);
        List<String> b = new ArrayList<>(now);
        a.sort(null);
        b.sort(null);
        return a.equals(b);
    }

    /** Sets the state of every lesson of the saved tree from what it was and what the author sent. */
    void reconcile(Long revisionId, Snapshot before, LearningRequests.DraftContent content) {
        List<CourseChapter> chapters = repository.chapters(revisionId);
        List<CourseLesson> lessons = repository.lessons(revisionId);
        for (int ci = 0; ci < chapters.size(); ci++) {
            List<CourseLesson> row = new ArrayList<>();
            for (CourseLesson lesson : lessons) {
                if (lesson.getChapterId().equals(chapters.get(ci).getId())) {
                    row.add(lesson);
                }
            }
            for (int li = 0; li < row.size(); li++) {
                LearningRequests.LessonInput sent = content.chapters().get(ci).lessons().get(li);
                Before old = ci < before.lessons().size() && li < before.lessons().get(ci).size()
                    ? before.lessons().get(ci).get(li) : null;
                String state = stateOf(old, sent);
                if (!state.equals(row.get(li).getAiState())) {
                    row.get(li).setAiState(state);
                    repository.save(row.get(li), row.get(li).getUpdatedBy());
                }
            }
        }
    }

    private static String stateOf(Before old, LearningRequests.LessonInput sent) {
        boolean empty = sent.blocks().isEmpty();
        if (old == null || old.state() == null) {
            return empty ? PENDING : USER_EDITED;
        }
        if (USER_EDITED.equals(old.state())) {
            return USER_EDITED;
        }
        if (old.fingerprint().equals(fingerprint(sent.blocks()))) {
            return old.state();
        }
        return empty && PENDING.equals(old.state()) ? PENDING : USER_EDITED;
    }

    private static String fingerprint(List<LessonBlock> blocks, Map<Long, List<ProcessStep>> steps) {
        StringBuilder text = new StringBuilder();
        for (LessonBlock block : blocks.stream().sorted((a, b) -> Integer.compare(a.getSortNo(), b.getSortNo()))
            .toList()) {
            text.append(block.getBlockType()).append('|').append(block.getTitle()).append('|')
                .append(Objects.toString(block.getBody(), "")).append('|')
                .append(Objects.toString(block.getFlowModel(), "")).append('|');
            for (ProcessStep step : steps.getOrDefault(block.getId(), List.of())) {
                text.append(step.getTitle()).append('/').append(Objects.toString(step.getDescription(), "")).append(';');
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static String fingerprint(List<LearningRequests.BlockInput> blocks) {
        StringBuilder text = new StringBuilder();
        for (LearningRequests.BlockInput block : blocks) {
            text.append(block.blockType()).append('|').append(block.title()).append('|')
                .append(Objects.toString(block.body(), "")).append('|')
                .append(Objects.toString(FlowModelJson.column(block.flow()), "")).append('|');
            for (LearningRequests.StepInput step : block.steps()) {
                text.append(step.title()).append('/').append(Objects.toString(step.description(), "")).append(';');
            }
            text.append('\n');
        }
        return text.toString();
    }
}
