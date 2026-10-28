package vn.com.coreacademy.assessment.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.entity.AttemptAnswer;
import vn.com.coreacademy.assessment.entity.AttemptQuestion;
import vn.com.coreacademy.assessment.entity.Competency;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;

/** Dựng read model từ snapshot lượt thi, dùng chung cho màn học viên và màn quản trị. */
@Component
@RequiredArgsConstructor
public class AttemptReadModel {
    private final AssessmentRepository repository;
    private final AttemptJson json;

    public Map<Long, AttemptAnswer> answersByQuestion(Long attemptId) {
        return repository.answers(attemptId).stream()
            .collect(Collectors.toMap(AttemptAnswer::getAttemptQuestionId, Function.identity()));
    }

    public AssessmentViews.ReviewQuestion review(AttemptQuestion question, AttemptAnswer answer) {
        return new AssessmentViews.ReviewQuestion(question.getDisplayOrder(), question.getQuestionType(),
            question.getContentSnapshot(), options(question, answer),
            answer != null && Boolean.TRUE.equals(answer.getCorrect()), question.getPoints(),
            answer == null ? BigDecimal.ZERO : answer.getScoreAwarded(), question.getExplanationSnapshot());
    }

    /** Bản đầy đủ cho quản trị/xử lý khiếu nại; correct NULL khi lượt chưa chấm. */
    public AssessmentViews.AdminAttemptQuestion adminQuestion(AttemptQuestion question, AttemptAnswer answer) {
        return new AssessmentViews.AdminAttemptQuestion(question.getId(), question.getDisplayOrder(),
            question.getQuestionId(), question.getQuestionVersion(), question.getQuestionType(),
            question.getContentSnapshot(), options(question, answer), answer == null ? null : answer.getCorrect(),
            question.getPoints(), answer == null ? null : answer.getScoreAwarded(), question.getExplanationSnapshot(),
            question.getFlagged(), answer == null ? null : answer.getAnsweredAt(),
            answer == null ? null : answer.getChangedAt(), answer == null ? 0 : answer.getChangeCount());
    }

    /** Kết quả theo nhóm năng lực dựa trên competency_id đã chụp lúc phát đề. */
    public List<AssessmentViews.CompetencyScore> competencies(List<AttemptQuestion> questions,
                                                              Map<Long, AttemptAnswer> answers) {
        Map<Long, List<AttemptQuestion>> groups = questions.stream().filter(q -> q.getCompetencyId() != null)
            .collect(Collectors.groupingBy(AttemptQuestion::getCompetencyId, LinkedHashMap::new, Collectors.toList()));
        Map<Long, Competency> names = repository.competencies(groups.keySet()).stream()
            .collect(Collectors.toMap(Competency::getId, Function.identity()));
        return groups.entrySet().stream().map(group -> {
            int correct = 0;
            BigDecimal max = BigDecimal.ZERO;
            BigDecimal score = BigDecimal.ZERO;
            for (AttemptQuestion question : group.getValue()) {
                AttemptAnswer answer = answers.get(question.getId());
                max = max.add(question.getPoints());
                if (answer != null && answer.getScoreAwarded() != null) {
                    score = score.add(answer.getScoreAwarded());
                }
                if (answer != null && Boolean.TRUE.equals(answer.getCorrect())) {
                    correct++;
                }
            }
            Competency competency = names.get(group.getKey());
            return new AssessmentViews.CompetencyScore(group.getKey(), competency == null ? null : competency.getCode(),
                competency == null ? null : competency.getName(), group.getValue().size(), correct, max, score);
        }).toList();
    }

    public static Long duration(QuizAttempt attempt) {
        return attempt.getSubmittedAt() == null ? null
            : Duration.between(attempt.getStartedAt(), attempt.getSubmittedAt()).toSeconds();
    }

    private List<AssessmentViews.ReviewOption> options(AttemptQuestion question, AttemptAnswer answer) {
        Set<Long> correct = Set.copyOf(json.ids(question.getCorrectOptionIds()));
        Set<Long> selected = answer == null ? Set.of() : Set.copyOf(json.ids(answer.getSelectedOptionIds()));
        return json.options(question.getOptionsSnapshot()).stream()
            .map(o -> new AssessmentViews.ReviewOption(o.id(), o.content(), correct.contains(o.id()),
                selected.contains(o.id()))).toList();
    }
}
