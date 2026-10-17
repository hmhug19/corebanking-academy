package vn.com.coreacademy.common.web;

import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import vn.com.coreacademy.common.exception.BusinessException;

public final class PageQuery {
    private PageQuery() {
    }

    /**
     * Tạo phân trang với page từ 0, size trong 1..100 và sort thuộc danh sách field được phép. Thêm id tăng
     * dần làm thứ tự phụ khi cần để các bản ghi trùng giá trị sort có vị trí ổn định.
     */
    public static PageRequest of(int page, int size, String sort, Set<String> allowed) {
        if (page < 0) {
            throw BusinessException.validation("page", "OUT_OF_RANGE", "Chỉ số trang phải từ 0 trở lên.");
        }
        if (size < 1 || size > 100) {
            throw BusinessException.validation("size", "OUT_OF_RANGE", "Số bản ghi mỗi trang phải từ 1 đến 100.");
        }
        if ((long) page * size > Integer.MAX_VALUE) {
            throw BusinessException.validation("page", "OUT_OF_RANGE", "Trang yêu cầu vượt phạm vi hỗ trợ.");
        }
        if (sort == null) {
            throw BusinessException.validation("sort", "REQUIRED", "Vui lòng cung cấp thứ tự sắp xếp.");
        }
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || !allowed.contains(parts[0])
            || (parts.length == 2 && !Set.of("asc", "desc").contains(parts[1]))) {
            throw BusinessException.validation("sort", "INVALID_VALUE", "Trường hoặc chiều sắp xếp không được hỗ trợ.");
        }
        Sort order = Sort.by(parts.length == 2 && "desc".equals(parts[1])
            ? Sort.Direction.DESC : Sort.Direction.ASC, parts[0]);
        if (!"id".equals(parts[0])) {
            order = order.and(Sort.by("id"));
        }
        return PageRequest.of(page, size, order);
    }
}
