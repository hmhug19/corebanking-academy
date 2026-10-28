package vn.com.coreacademy.assessment.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ItemStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.SelectionMode;
import vn.com.coreacademy.assessment.dto.AssessmentViews.OptionText;
import vn.com.coreacademy.assessment.entity.Question;
import vn.com.coreacademy.assessment.entity.QuestionOption;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.assessment.entity.QuizQuestion;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.common.exception.BusinessException;

/**
 * Bốc đề theo cấu hình hiện tại của bài thi; dùng chung cho lượt thi thật và màn xem thử của giảng viên để hai nơi
 * luôn cùng quy tắc. Không ghi dữ liệu.
 */
@Component
@RequiredArgsConstructor
public class QuestionDrawer {
    private static final Random RANDOM = new SecureRandom();

    private final AssessmentRepository repository;

    /** Một câu đã bốc: phương án theo thứ tự hiển thị, ID đáp án đúng và điểm áp dụng trong đề. */
    public record Drawn(Question question, List<QuestionOption> choices, List<Long> correctIds, BigDecimal points) {
        public List<OptionText> options() {
            return choices.stream().map(o -> new OptionText(o.getId(), o.getContent())).toList();
        }
    }

    /**
     * FIXED lấy pool theo sort_no; RANDOM_POOL bốc ngẫu nhiên question_count câu ACTIVE. Trộn câu/phương án nếu
     * bật. Thiếu câu hoặc câu thiếu phương án hợp lệ thì chặn để không phát đề sai.
     */
    public List<Drawn> draw(Quiz quiz) {
        List<QuizQuestion> pool = repository.pool(quiz.getId());
        Map<Long, Question> questions = repository.questions(pool.stream().map(QuizQuestion::getQuestionId).toList())
            .stream().filter(q -> !Boolean.TRUE.equals(q.getDeleted()) && ItemStatus.ACTIVE.equals(q.getStatus()))
            .collect(Collectors.toMap(Question::getId, Function.identity()));
        List<QuizQuestion> eligible = new ArrayList<>(pool.stream()
            .filter(p -> questions.containsKey(p.getQuestionId())).toList());
        if (eligible.size() < quiz.getQuestionCount()) {
            throw new BusinessException(AssessmentResponseCode.QUIZ_NOT_READY);
        }

        Comparator<QuizQuestion> poolOrder = Comparator.comparing(QuizQuestion::getSortNo)
            .thenComparing(QuizQuestion::getId);
        List<QuizQuestion> selected;
        if (SelectionMode.RANDOM_POOL.equals(quiz.getSelectionMode())) {
            Collections.shuffle(eligible, RANDOM);
            selected = new ArrayList<>(eligible.subList(0, quiz.getQuestionCount()));
            if (!quiz.getShuffleQuestions()) {
                selected.sort(poolOrder);
            }
        } else {
            eligible.sort(poolOrder);
            selected = new ArrayList<>(eligible.subList(0, quiz.getQuestionCount()));
            if (quiz.getShuffleQuestions()) {
                Collections.shuffle(selected, RANDOM);
            }
        }

        Map<Long, List<QuestionOption>> options = repository.options(selected.stream()
            .map(QuizQuestion::getQuestionId).toList()).stream()
            .collect(Collectors.groupingBy(QuestionOption::getQuestionId));
        List<Drawn> drawn = new ArrayList<>();
        for (QuizQuestion entry : selected) {
            Question question = questions.get(entry.getQuestionId());
            List<QuestionOption> choices = new ArrayList<>(options.getOrDefault(question.getId(), List.of()));
            List<Long> correct = choices.stream().filter(QuestionOption::getCorrect).map(QuestionOption::getId)
                .toList();
            if (choices.size() < 2 || correct.isEmpty()) {
                throw new BusinessException(AssessmentResponseCode.QUIZ_NOT_READY);
            }
            if (quiz.getShuffleOptions()) {
                Collections.shuffle(choices, RANDOM);
            }
            BigDecimal points = entry.getPoints() == null ? question.getPoints() : entry.getPoints();
            drawn.add(new Drawn(question, choices, correct, points));
        }
        return drawn;
    }
}
