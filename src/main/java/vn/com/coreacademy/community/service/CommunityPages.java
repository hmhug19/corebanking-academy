package vn.com.coreacademy.community.service;

import java.util.List;
import java.util.function.Function;

import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.constants.CommunityLimits;

public final class CommunityPages {
    public static void check(int page, int size) {
        if (page < 0 || size < 1 || size > CommunityLimits.PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw BusinessException.validation("page", "INVALID_VALUE", "Trang hoặc kích thước trang không hợp lệ.");
        }
    }

    public static <T> PageResponse<T> of(List<T> items, int page, int size, long total) {
        return new PageResponse<>(List.copyOf(items), page, size, total, (int) ((total + size - 1) / size));
    }

    public static <T, R> PageResponse<R> map(PageResponse<T> page, Function<T, R> mapper) {
        return new PageResponse<>(page.items().stream().map(mapper).toList(), page.page(), page.size(),
            page.totalElements(), page.totalPages());
    }

    private CommunityPages() {
    }
}
