package vn.com.coreacademy.assessment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuestionType;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ScoringMode;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.SubmitType;
import vn.com.coreacademy.assessment.entity.AttemptAnswer;
import vn.com.coreacademy.assessment.entity.AttemptQuestion;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.assessment.repository.custom.AssessmentResultRepository;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;

/**
 * Chấm lượt thi chỉ dựa trên snapshot attempt_questions và đáp án đã lưu, không đọc lại câu hỏi gốc. Bên gọi phải
 * giữ khóa dòng của lượt (AssessmentRepository.lockAttempt) trong cùng transaction.
 */
@Component
@RequiredArgsConstructor
public class AttemptGrader {
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final AssessmentRepository repository;
    private final AttemptJson json;
    private final LearningAssessmentAccess learning;
    private final AssessmentResultRepository results;

    public boolean expired(QuizAttempt attempt, Instant now) {
        return now.isAfter(attempt.getDeadlineAt().plusSeconds(attempt.getGraceSeconds()));
    }

    /**
     * Nộp bài theo yêu cầu học viên. Quá deadline + ân hạn thì coi là hệ thống tự nộp với giờ nộp bằng deadline, để
     * kết quả và thời gian chờ thi lại không phụ thuộc lúc request hoặc job chạy tới.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void submit(QuizAttempt attempt, Instant now) {
        if (expired(attempt, now)) {
            finalizeExpired(attempt);
        } else {
            grade(attempt, AttemptStatus.SUBMITTED, SubmitType.MANUAL, now);
        }
    }

    /** Tự nộp lượt đã quá hạn; TIMEOUT nếu hết thời lượng làm bài, QUIZ_CLOSED nếu bị cắt bởi giờ đóng đề. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void finalizeExpired(QuizAttempt attempt) {
        Instant fullDeadline = attempt.getStartedAt().plus(Duration.ofMinutes(attempt.getTimeLimitMinutes()));
        String submitType = attempt.getDeadlineAt().isBefore(fullDeadline) ? SubmitType.QUIZ_CLOSED
            : SubmitType.TIMEOUT;
        grade(attempt, AttemptStatus.AUTO_SUBMITTED, submitType, attempt.getDeadlineAt());
    }

    /**
     * Nộp thay học viên (quản trị nộp hộ hoặc đóng đề). Giờ nộp không vượt deadline để thời gian làm bài và thời gian
     * chờ thi lại không bị kéo dài bởi lúc quản trị thao tác.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void submitOnBehalf(QuizAttempt attempt, String status, String submitType, Instant now) {
        Instant submittedAt = now.isAfter(attempt.getDeadlineAt()) ? attempt.getDeadlineAt() : now;
        grade(attempt, status, submitType, submittedAt);
    }

    private void grade(QuizAttempt attempt, String status, String submitType, Instant submittedAt) {
        List<AttemptQuestion> questions = repository.attemptQuestions(attempt.getId());
        Map<Long, AttemptAnswer> answers = repository.answers(attempt.getId()).stream()
            .collect(Collectors.toMap(AttemptAnswer::getAttemptQuestionId, Function.identity()));

        BigDecimal raw = BigDecimal.ZERO;
        int correctCount = 0;
        for (AttemptQuestion question : questions) {
            AttemptAnswer answer = answers.get(question.getId());
            if (answer == null) {
                // Câu bỏ trống vẫn có dòng đáp án rỗng để báo cáo phân biệt "bỏ trống" và "chưa chấm".
                answer = new AttemptAnswer();
                answer.setAttemptQuestionId(question.getId());
                answer.setSelectedOptionIds(json.write(List.of()));
                answer.setAnsweredAt(submittedAt);
                repository.persist(answer);
            }
            Set<Long> selected = new HashSet<>(json.ids(answer.getSelectedOptionIds()));
            Set<Long> correct = new HashSet<>(json.ids(question.getCorrectOptionIds()));
            boolean exact = selected.equals(correct);
            BigDecimal score = score(attempt.getScoringMode(), question, selected, correct, exact);
            answer.setCorrect(exact);
            answer.setScoreAwarded(score);
            raw = raw.add(score);
            if (exact) {
                correctCount++;
            }
        }

        applyTotals(attempt, raw, correctCount);
        attempt.setStatus(status);
        attempt.setSubmitType(submitType);
        attempt.setSubmittedAt(submittedAt);
        completeQuizLesson(attempt);
        results.refresh(attempt);
    }

    /**
     * Đạt đề thì bài quiz của khóa tự hoàn thành. Nộp, hết giờ và nộp hộ đều đi qua đây; chấm lại tự gọi hook
     * tương tự. Tiến độ chỉ tăng, không bao giờ hạ.
     */
    private void completeQuizLesson(QuizAttempt attempt) {
        if (Boolean.TRUE.equals(attempt.getPassed()) && attempt.getEnrollmentId() != null) {
            learning.quizPassed(attempt.getUserId(), attempt.getEnrollmentId(), attempt.getQuizId());
        }
    }

    /**
     * Ghi tổng điểm của lượt; dùng chung cho chấm khi nộp và chấm lại. So sánh đạt trên giá trị chưa làm tròn
     * (raw*100 >= pass*max); điểm % lưu cắt xuống để 69,995 không hiện 70.
     */
    static void applyTotals(QuizAttempt attempt, BigDecimal raw, int correctCount) {
        attempt.setCorrectCount(correctCount);
        attempt.setRawScore(raw);
        attempt.setScorePercent(percent(raw, attempt.getMaxScore()));
        attempt.setPassed(passes(raw, attempt.getMaxScore(), attempt.getPassPercent()));
        attempt.setGradedAt(ServerTime.now());
    }

    static BigDecimal percent(BigDecimal raw, BigDecimal max) {
        return raw.multiply(HUNDRED).divide(max, 2, RoundingMode.DOWN);
    }

    static boolean passes(BigDecimal raw, BigDecimal max, BigDecimal passPercent) {
        return raw.multiply(HUNDRED).compareTo(passPercent.multiply(max)) >= 0;
    }

    /**
     * ALL_OR_NOTHING và câu SINGLE: đúng đủ mới có điểm. PARTIAL (câu MULTIPLE): points × max(0, đúng − sai) / số
     * đáp án đúng, làm tròn 2 chữ số.
     */
    static BigDecimal score(String scoringMode, AttemptQuestion question, Set<Long> selected, Set<Long> correct,
                            boolean exact) {
        if (QuestionType.SINGLE.equals(question.getQuestionType()) || !ScoringMode.PARTIAL.equals(scoringMode)) {
            return exact ? question.getPoints() : BigDecimal.ZERO;
        }
        long right = selected.stream().filter(correct::contains).count();
        long wrong = selected.size() - right;
        long net = Math.max(0, right - wrong);
        return question.getPoints().multiply(BigDecimal.valueOf(net))
            .divide(BigDecimal.valueOf(correct.size()), 2, RoundingMode.HALF_UP);
    }
}
