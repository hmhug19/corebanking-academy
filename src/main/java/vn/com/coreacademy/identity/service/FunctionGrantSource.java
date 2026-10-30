package vn.com.coreacademy.identity.service;

import java.util.List;

import vn.com.coreacademy.identity.dto.Scope;

/**
 * Chức năng màn hình do module khác cấp theo dữ liệu nghiệp vụ của nó, ngoài mapping vai trò Identity.
 * Chỉ dùng để hiển thị menu; module sở hữu vẫn kiểm quyền hành động ở từng API.
 */
public interface FunctionGrantSource {
    record FunctionGrant(String functionCode, Scope scope) {
    }

    /** Chức năng người dùng đang có trong đơn vị hiện tại; rỗng khi không có. */
    List<FunctionGrant> grants(String userId, Long orgId);
}
