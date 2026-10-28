package vn.com.coreacademy.common.search;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;

/** Hợp nhất projection đã lọc ACL tại module nguồn; không cắt từng nguồn trong RAM làm sai page/count. */
@Repository
@RequiredArgsConstructor
public class SearchQueryRepository {
    private final EntityManager entityManager;

    public PageResponse<Object[]> search(List<SearchContributor.Part> parts, int page, int size, String sort) {
        if (parts.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0, 0);
        }
        String order = Map.of("rank", "score desc, source_type, source_id", "updatedAt",
            "updated_epoch desc, source_type, source_id").get(sort);
        if (order == null) {
            throw BusinessException.validation("sort", "INVALID_VALUE", "Chỉ hỗ trợ rank hoặc updatedAt.");
        }
        String union = parts.stream().map(part -> "(" + part.sql() + ")").collect(Collectors.joining(" union all "));
        String from = " from (" + union + ") search_result ";
        Query count = entityManager.createNativeQuery("select count(*)" + from);
        Query data = entityManager.createNativeQuery("select *" + from + " order by " + order);
        parts.forEach(part -> part.parameters().forEach((key, value) -> {
            count.setParameter(key, value);
            data.setParameter(key, value);
        }));
        long total = ((Number) count.getSingleResult()).longValue();
        data.setFirstResult(Math.multiplyExact(page, size)).setMaxResults(size);
        List<Object[]> items = data.getResultList().stream().map(Object[].class::cast).toList();
        return new PageResponse<>(items, page, size, total, (int) ((total + size - 1) / size));
    }
}
