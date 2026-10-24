package vn.com.coreacademy.common.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import vn.com.coreacademy.common.exception.BusinessException;

/**
 * Ghép các mệnh đề SQL do repository định nghĩa; giá trị client luôn bind parameter.
 * Mỗi lượt search tạo một instance, dùng chung FROM/WHERE cho list và count; không giữ state trong bean.
 */
public final class NativeSearchQuery {
    private final String from;
    private final StringBuilder where = new StringBuilder("where 1 = 1\n");
    private final Map<String, Object> parameters = new LinkedHashMap<>();
    private final Map<String, Object> projectionParameters = new LinkedHashMap<>();

    public NativeSearchQuery(String from) {
        this.from = from;
    }

    public void and(String condition) {
        where.append("and (").append(condition.strip()).append(")\n");
    }

    public void bind(String name, Object value) {
        parameters.put(name, value);
    }

    /** Giá trị chỉ có trong SELECT không được bind vào COUNT dùng chung FROM/WHERE. */
    public void bindProjection(String name, Object value) {
        projectionParameters.put(name, value);
    }

    public void in(String column, String parameter, Collection<?> values) {
        if (values.isEmpty()) {
            and("1 = 0");
        } else {
            and(column + " in (:" + parameter + ")");
            bind(parameter, values);
        }
    }

    /**
     * Escape ký tự LIKE để q luôn là chuỗi literal; dấu ! tránh phụ thuộc escape backslash của DB.
     */
    public static String containsPattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }

        if (q.length() > 100) {
            throw BusinessException.validation("q", "TOO_LONG", "Từ khóa tìm kiếm không vượt quá 100 ký tự.");
        }

        return "%" + q.trim().toLowerCase(Locale.ROOT).replace("!", "!!")
            .replace("%", "!%").replace("_", "!_") + "%";
    }

    /**
     * Phân trang tại DB, sort qua whitelist; chỉ bỏ count khi suy ra chính xác từ trang kết quả.
     */
    public <T> Page<T> page(EntityManager entityManager, String select, Class<T> type, Pageable pageable,
                            Map<String, String> sortColumns) {
        if (pageable.isUnpaged() || pageable.getOffset() > Integer.MAX_VALUE) {
            throw BusinessException.validation("page", "OUT_OF_RANGE", "Trang yêu cầu vượt phạm vi hỗ trợ.");
        }

        String orderBy = buildOrderBy(pageable, sortColumns);
        String source = from + where;
        Query query = entityManager.createNativeQuery(select + source + "order by " + orderBy, type);
        parameters.forEach(query::setParameter);
        projectionParameters.forEach(query::setParameter);
        query.setFirstResult((int) pageable.getOffset()).setMaxResults(pageable.getPageSize());

        List<?> rows = query.getResultList();
        List<T> content = rows.stream().map(type::cast).toList();

        return PageableExecutionUtils.getPage(content, pageable, () -> {
            Query count = entityManager.createNativeQuery("select count(*)\n" + source);
            parameters.forEach(count::setParameter);
            return ((Number) count.getSingleResult()).longValue();
        });
    }

    private String buildOrderBy(Pageable pageable, Map<String, String> sortColumns) {
        String orderBy = pageable.getSort().stream().map(order -> {
            String column = sortColumns.get(order.getProperty());
            if (column == null) {
                throw BusinessException.validation("sort", "INVALID_VALUE", "Trường sắp xếp không được hỗ trợ.");
            }
            return column + (order.isAscending() ? " asc" : " desc");
        }).collect(Collectors.joining(", "));

        if (orderBy.isEmpty()) {
            throw new IllegalArgumentException("A deterministic sort is required");
        }
        return orderBy;
    }
}
