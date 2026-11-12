package vn.com.coreacademy.learning.index;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.service.ReindexSource;

/**
 * Indexed course revisions with chunks of another chunker version or embed model, for every revision still kept
 * (the published one and those with active enrolments). The indexer swaps the chunks of a revision in one transaction.
 */
@Component
@RequiredArgsConstructor
public class CourseReindexSource implements ReindexSource {
    private final CourseContentChunkRepository index;
    private final CourseContentIndexer indexer;

    @Override
    public String kind() {
        return "course_revision";
    }

    @Override
    public List<Item> stale() {
        return index.staleRevisions().stream()
            .map(revision -> new Item(revision.revisionId(), () -> indexer.reindex(revision))).toList();
    }
}
