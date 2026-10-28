package vn.com.coreacademy.common.search;

import java.util.List;
import java.util.Map;

/** Projection SQL tin cậy do module sở hữu tạo; request không bao giờ nhận SQL/tên bảng từ client. */
public interface SearchContributor {
    record Filter(String q, List<Long> tagIds, Long guildId, String kind) {
    }

    record Part(String sourceType, String sql, Map<String, Object> parameters) {
    }

    String sourceType();

    Part contribute(Filter filter);
}
