package vn.com.coreacademy.learning.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import vn.com.coreacademy.common.mapper.CentralMapperConfig;
import vn.com.coreacademy.learning.dto.LearningNoteViews.Anchor;
import vn.com.coreacademy.learning.dto.LearningNoteViews.BookmarkView;
import vn.com.coreacademy.learning.dto.LearningNoteViews.NoteView;
import vn.com.coreacademy.learning.entity.LessonBookmark;
import vn.com.coreacademy.learning.entity.LessonNote;

@Mapper(config = CentralMapperConfig.class)
public interface LessonNoteMapper {
    @Mapping(target = "text", source = "body")
    @Mapping(target = "anchor",
        expression = "java(anchor(value.getAnchorType(), value.getBlockId(), value.getPdfPage(), "
            + "value.getVideoSecond()))")
    NoteView view(LessonNote value);

    @Mapping(target = "anchor",
        expression = "java(anchor(value.getAnchorType(), value.getBlockId(), value.getPdfPage(), "
            + "value.getVideoSecond()))")
    BookmarkView view(LessonBookmark value);

    default Anchor anchor(String type, Long blockId, Integer page, Integer second) {
        return new Anchor(type, blockId, page, second);
    }
}
