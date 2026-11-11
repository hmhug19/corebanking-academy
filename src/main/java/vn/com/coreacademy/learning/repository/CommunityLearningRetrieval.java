package vn.com.coreacademy.learning.repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.search.KnowledgeSource;
import vn.com.coreacademy.learning.index.CourseContentRetrieval;
import vn.com.coreacademy.learning.index.CourseContentRetrieval.CourseHit;
import vn.com.coreacademy.learning.service.CommunityLearningAdapter;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;

/** Tìm nội dung bài học trong chỉ mục khóa học đã xuất bản; quyền đọc do CourseContentRetrieval áp dụng (quản lý hoặc enrollment). */
@Repository
@RequiredArgsConstructor
public class CommunityLearningRetrieval implements KnowledgeSource {
    private final CourseContentRetrieval index;
    private final CommunityLearningAdapter adapter;
    private final LearningRepository repository;
    private final LearningAccessPolicy access;

    @Override
    public String sourceType() {
        return "learning";
    }

    @Override
    public List<Excerpt> retrieve(float[] vector, String question, Long guildId, int limit, String model) {
        return index.search(vector, limit).stream().map(CommunityLearningRetrieval::excerpt).toList();
    }

    private static Excerpt excerpt(CourseHit hit) {
        return new Excerpt(new Pointer("learning", hit.courseId(), hit.revisionId(), hit.chunkId()),
            hit.courseTitle() + " › " + hit.lessonTitle(), hit.content(), hit.url(),
            Map.of("courseRevisionId", hit.revisionId(), "lessonId", hit.lessonId()), hit.similarity());
    }

    @Override
    public Excerpt resolve(Pointer pointer) {
        if (pointer.chunkId() != null) {
            return index.find(pointer.chunkId()).map(CommunityLearningRetrieval::excerpt).orElse(null);
        }
        // Pointers saved before the course index name a revision only; they still resolve to the revision text.
        try {
            var context = adapter.readable(pointer.revisionId());
            if (pointer.sourceId() != null && !Objects.equals(context.courseId(), pointer.sourceId())
                || !"published".equals(context.state())) {
                return null;
            }
            String text = repository.blocks(context.revisionId()).stream()
                .map(block -> block.getTitle() + "\n" + (block.getBody() == null ? "" : block.getBody()))
                .collect(Collectors.joining("\n"));
            text = context.title() + "\n" + text;
            return new Excerpt(new Pointer(sourceType(), context.courseId(), context.revisionId(), null), context.title(),
                text.substring(0, Math.min(2000, text.length())), "/learning/courses/" + context.courseId()
                    + "?revisionId=" + context.revisionId(), Map.of("courseRevisionId", context.revisionId()), 1);
        } catch (BusinessException | AccessDeniedException exception) {
            return null;
        }
    }
}
