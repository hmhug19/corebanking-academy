package vn.com.coreacademy.knowledge.service;

import java.util.List;

/**
 * Something whose chunks can be out of date: documents, course revisions. The re-index service asks each source for
 * the items cut by another chunker version or embedded by another model than the configured ones, and runs them.
 */
public interface ReindexSource {
    /** One stale thing; {@code run} re-does its chunks and swaps them in atomically, or throws and changes nothing. */
    record Item(long id, Runnable run) {
    }

    /** A short name for logs, such as {@code document}. */
    String kind();

    /** The items that are stale now. */
    List<Item> stale();
}
