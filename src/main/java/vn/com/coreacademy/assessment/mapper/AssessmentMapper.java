package vn.com.coreacademy.assessment.mapper;

import org.mapstruct.Mapper;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.entity.Competency;
import vn.com.coreacademy.assessment.entity.QuestionOption;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;

@Mapper(config = CentralMapperConfig.class)
public interface AssessmentMapper {
    AssessmentViews.CompetencyView view(Competency value);

    AssessmentViews.OptionView view(QuestionOption value);

    AssessmentViews.QuizView view(Quiz value);
}
