package vn.com.coreacademy.identity.dto;

import vn.com.coreacademy.identity.enums.ScopeType;

/**
 * Scope trả về dùng field type; request cấp quyền dùng field scope trong GrantRequest.
 * Không tách scope khỏi vai trò tương ứng để suy quyền giữa các grant khác nhau.
 */
public record Scope(ScopeType type, Long orgId, Long teamId) {
}
