package vn.com.coreacademy.assessment.service;

import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ItemStatus;
import vn.com.coreacademy.assessment.entity.Question;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.assessment.repository.FlashcardRepository;
import vn.com.coreacademy.assessment.repository.FlashcardRepository.Answer;
import vn.com.coreacademy.assessment.repository.FlashcardRepository.ManagedCard;
import vn.com.coreacademy.assessment.repository.FlashcardRepository.StudyCard;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LearningWriteLock;

@Service
@RequiredArgsConstructor
public class FlashcardService {
    private final FlashcardRepository cards;
    private final AssessmentRepository assessment;
    private final LearningAssessmentAccess access;
    private final LearningWriteLock lock;

    @Transactional(readOnly = true)
    public List<ManagedCard> managed(long courseId) {
        access.requireManagedCourse(courseId);
        return cards.managed(courseId);
    }

    @Transactional
    public List<ManagedCard> change(long courseId, long questionId, String status, Long questionVersion) {
        lock.acquire();
        access.requireManagedCourse(courseId);
        String actor = access.actor().person().id();
        if ("WITHDRAWN".equals(status)) {
            if (!cards.withdraw(courseId, questionId, actor)) {
                throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
            }
            return cards.managed(courseId);
        }
        Question question = assessment.require(Question.class, questionId);
        if (question.getProgramId() != null) {
            access.requireCourseProgramBank(courseId, question.getProgramId());
            access.requireManagedProgram(question.getProgramId());
        } else if (!Objects.equals(question.getCourseId(), courseId)) {
            throw new BusinessException(AssessmentResponseCode.INVALID_REFERENCE);
        }
        if (!ItemStatus.ACTIVE.equals(question.getStatus()) || Boolean.TRUE.equals(question.getDeleted())) {
            throw new BusinessException(AssessmentResponseCode.INVALID_REFERENCE);
        }
        Long approvedVersion = null;
        if ("PUBLISHED".equals(status)) {
            if (!Objects.equals(questionVersion, question.getRowVersion())) {
                throw new BusinessException(AssessmentResponseCode.CONFLICT);
            }
            if (cards.inFinalExam(questionId)) {
                throw BusinessException.validation("questionId", "FINAL_EXAM_QUESTION",
                    "Câu hỏi đang thuộc đề thi cuối khóa.");
            }
            approvedVersion = question.getRowVersion();
        }
        cards.setStatus(courseId, questionId, status, approvedVersion, actor);
        return cards.managed(courseId);
    }

    @Transactional(readOnly = true)
    public List<StudyCard> study(long courseId) {
        var learner = access.requireStudyingLearner(courseId);
        return cards.study(courseId, learner.revisionId(), learner.userId());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Answer reveal(long courseId, long cardId, long version) {
        var learner = access.requireStudyingLearner(courseId);
        return cards.answer(requireVisible(courseId, learner.revisionId(), cardId, version));
    }

    @Transactional
    public void save(long courseId, long cardId, long version, String rating, Boolean starred) {
        if (rating == null && starred == null) {
            throw BusinessException.validation("rating", "REQUIRED", "Chọn đánh giá hoặc trạng thái đánh dấu.");
        }
        lock.acquire();
        var learner = access.requireStudyingLearner(courseId);
        requireVisible(courseId, learner.revisionId(), cardId, version);
        cards.saveProgress(learner.userId(), cardId, version, rating, starred);
    }

    private long requireVisible(long courseId, long revisionId, long cardId, long version) {
        return cards.question(courseId, revisionId, cardId, version)
            .orElseThrow(() -> new BusinessException(AssessmentResponseCode.NOT_FOUND));
    }
}
