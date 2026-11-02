package vn.com.coreacademy.learning.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.learning.dto.LearningFeedbackViews.FeedbackView;
import vn.com.coreacademy.learning.dto.LearningNoteViews.Anchor;
import vn.com.coreacademy.learning.entity.LessonFeedback;

@Mapper(config = CentralMapperConfig.class)
public interface LessonFeedbackMapper {
    @Mapping(target = "text", source = "body")
    @Mapping(target = "anchor",
        expression = "java(anchor(value.getAnchorType(), value.getBlockId(), value.getPdfPage(), "
            + "value.getVideoSecond()))")
    FeedbackView view(LessonFeedback value);

    default Anchor anchor(String type, Long blockId, Integer page, Integer second) {
        return new Anchor(type, blockId, page, second);
    }
}
