package vn.com.coreacademy.assessment.service;

import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuestionType;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.repository.CheckpointRepository;
import vn.com.coreacademy.assessment.repository.CheckpointRepository.Candidate;
import vn.com.coreacademy.assessment.repository.CheckpointRepository.Graded;
import vn.com.coreacademy.assessment.repository.CheckpointRepository.Option;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LessonAnchors;
import vn.com.coreacademy.learning.service.LessonAnchors.LessonAccess;

/**
 * Câu hỏi nhanh cuối bài: nhắc lại kiến thức, không tính điểm. Không đổi XP, hoàn thành bài, tiến độ hay kết quả năng
 * lực; câu trả lời chỉ được lưu để chọn câu lần sau và gợi ý ôn tập. Đáp án đúng chỉ trả sau khi học viên đã trả lời.
 */
@Service
@RequiredArgsConstructor
public class CheckpointService {
    private final CheckpointRepository checkpoints;
    private final LessonAnchors lessons;
    private final LearningAssessmentAccess access;

    public record CheckpointQuestion(long questionId, long questionVersion, String questionType, String content,
                                     List<Option> options) {
    }

    public record CheckpointView(long lessonId, List<CheckpointQuestion> questions) {
    }

    public record CheckpointResult(boolean correct, Set<Long> correctOptionIds, String explanation) {
    }

    /** Tối đa 3 câu; bài không có flashcard đã công bố trả danh sách rỗng. Người không đọc được bài nhận NOT_FOUND. */
    @Transactional(readOnly = true)
    public CheckpointView checkpoint(long lessonId) {
        LessonAccess lesson = lessons.requireLessonAccess(lessonId);
        long revisionId = access.requireStudyingLearner(lesson.courseId()).revisionId();
        List<CheckpointQuestion> questions = checkpoints.pick(lesson.courseId(), revisionId, lessonId,
            lesson.userId(), AssessmentLimits.CHECKPOINT_QUESTIONS).stream().map(this::view).toList();
        return new CheckpointView(lessonId, questions);
    }

    /**
     * Chấm trên server và ghi một dòng câu trả lời. Câu không còn là câu hỏi nhanh của bài hoặc đã đổi phiên bản là
     * CHECKPOINT_STALE để FE tải lại thay vì chấm theo đáp án cũ.
     */
    @Transactional
    public CheckpointResult answer(long lessonId, long questionId, long questionVersion, Set<Long> optionIds) {
        LessonAccess lesson = lessons.requireLessonAccess(lessonId);
        long revisionId = access.requireStudyingLearner(lesson.courseId()).revisionId();
        Graded question = checkpoints.visible(lesson.courseId(), revisionId, lessonId, questionId, questionVersion)
            .orElseThrow(() -> new BusinessException(AssessmentResponseCode.CHECKPOINT_STALE));
        requireValidSelection(question, optionIds);

        Set<Long> correctOptionIds = checkpoints.correctOptionIds(questionId);
        boolean correct = correctOptionIds.equals(optionIds);
        checkpoints.record(lesson.userId(), lessonId, question, correct);
        return new CheckpointResult(correct, correctOptionIds, blankToNull(question.explanation()));
    }

    private CheckpointQuestion view(Candidate candidate) {
        return new CheckpointQuestion(candidate.questionId(), candidate.questionVersion(), candidate.questionType(),
            candidate.content(), checkpoints.options(candidate.questionId()));
    }

    private void requireValidSelection(Graded question, Set<Long> optionIds) {
        if (!checkpoints.optionIds(question.questionId()).containsAll(optionIds)) {
            throw BusinessException.validation("optionIds", "INVALID_VALUE", "Phương án không thuộc câu hỏi này.");
        }
        if (QuestionType.SINGLE.equals(question.questionType()) && optionIds.size() != 1) {
            throw BusinessException.validation("optionIds", "INVALID_VALUE", "Câu hỏi này chỉ chọn một phương án.");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
