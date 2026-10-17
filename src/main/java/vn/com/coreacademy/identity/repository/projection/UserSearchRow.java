package vn.com.coreacademy.identity.repository.projection;

/** Read model cho danh sách; không tải mật khẩu hoặc metadata nguồn không dùng trong UserSummary. */
public record UserSearchRow(String id, String username, String fullName, String email, String status,
                            String authProvider, boolean mustChangePassword, Long orgId, Long teamId,
                            String positionName) {
}
