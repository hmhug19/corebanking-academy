package vn.com.coreacademy.common.search;

import java.util.List;
import java.util.Map;

/** Cổng retrieval có quyền do module sở hữu kiểm tra; pointer không mang excerpt riêng tư qua DB metadata lượt AI. */
public interface KnowledgeSource {
    record Pointer(String sourceType, Long sourceId, Long revisionId, Long chunkId) {
    }

    record Excerpt(Pointer pointer, String title, String text, String url, Map<String, Object> locator,
                     double similarity) {
    }

    String sourceType();

    List<Excerpt> retrieve(float[] vector, String question, Long guildId, int limit, String model);

    Excerpt resolve(Pointer pointer);
}
