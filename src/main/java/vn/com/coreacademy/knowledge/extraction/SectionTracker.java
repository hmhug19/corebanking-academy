package vn.com.coreacademy.knowledge.extraction;

import java.util.ArrayList;
import java.util.List;

/** Giữ chuỗi tiêu đề hiện hành: một tiêu đề cấp n thay thế mọi tiêu đề từ cấp n trở xuống. */
final class SectionTracker {
    private final List<String> titles = new ArrayList<>();

    void enter(int level, String title) {
        int depth = Math.max(1, level);
        while (titles.size() >= depth) {
            titles.remove(titles.size() - 1);
        }
        titles.add(title);
    }

    List<String> path() {
        return List.copyOf(titles);
    }
}
