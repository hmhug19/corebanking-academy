package vn.com.coreacademy.identity.service;

import java.util.List;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class LoginUserResolver {
    private final UserRepository users;

    /**
     * Tra tài khoản bằng mã cán bộ giữ nguyên hoặc tên đăng nhập chuẩn hóa chữ thường. Chỉ chấp nhận đúng một
     * kết quả để tránh chọn nhầm khi alias trùng chéo; dùng lỗi đăng nhập chung để không tiết lộ tài khoản.
     */
    public UserAccount resolve(String login) {
        if (login == null || login.isBlank()) {
            throw new UsernameNotFoundException("Invalid credentials");
        }

        String identifier = login.trim();
        List<UserAccount> candidates = users.findLoginCandidates(identifier, identifier.toLowerCase(Locale.ROOT));
        if (candidates.size() != 1) {
            throw new UsernameNotFoundException("Invalid credentials");
        }

        return candidates.get(0);
    }
}
