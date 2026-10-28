package vn.com.coreacademy.community.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;

public final class LearningResourceDtos {
    public record ResourceInput(@Positive Long lessonId, @NotNull @Positive Long contentRevisionId,
                                 @NotNull @Min(0) Integer sortNo) {
    }

    public record Resources(@NotNull @Min(0) Long expectedCourseRevisionVersion,
                             @NotNull @Size(max = 100) List<@NotNull @Valid ResourceInput> items) {
    }

    public record LearningResourceView(Long learningLinkId, Long courseRevisionId, Long lessonId, ContentRef content,
                                        int sortNo, String status, List<String> capabilities) {
    }

    public record ResourceResult(Long courseRevisionVersion, List<LearningResourceView> items) {
    }

    private LearningResourceDtos() {
    }
}
