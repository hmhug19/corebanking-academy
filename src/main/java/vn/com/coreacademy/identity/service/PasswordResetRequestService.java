package vn.com.coreacademy.identity.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.identity.dto.RegistrationResult;
import vn.com.coreacademy.identity.entity.AccessRequest;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.AccessRequestKind;
import vn.com.coreacademy.identity.enums.AccessRequestStatus;
import vn.com.coreacademy.identity.repository.AccessRequestRepository;
import vn.com.coreacademy.identity.repository.UserRepository;

/**
 * The public side of "forgot password". The answer is the same whether an account has the code, none does, or a
 * request is already waiting, so the endpoint cannot be used to find accounts. A matched code stores the account
 * and its unit; an unknown code stores a row with neither, which an administrator can only reject. Not
 * transactional on purpose: a lost race on the one-pending-per-code index is caught here.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetRequestService {
    public static final String ACCEPTED_MESSAGE = "Yêu cầu đã được gửi. Quản trị viên sẽ xem xét; "
        + "nếu mã cán bộ hợp lệ, bạn sẽ được cấp mật khẩu tạm.";

    private final AccessRequestRepository requests;
    private final UserRepository users;
    private final AccessRequestThrottle throttle;

    public RegistrationResult request(String ip, String staffCodeInput) {
        String staffCode = staffCodeInput.trim();
        throttle.check(ip, AccessRequestKind.PASSWORD_RESET, staffCode);

        if (!requests.existsByKindAndStatusAndStaffCodeIgnoreCase(AccessRequestKind.PASSWORD_RESET,
            AccessRequestStatus.PENDING, staffCode)) {
            List<UserAccount> matches = users.findByIdentifier(staffCode);
            UserAccount account = matches.size() == 1 ? matches.get(0) : null;
            AccessRequest row = new AccessRequest();
            row.setKind(AccessRequestKind.PASSWORD_RESET);
            row.setStaffCode(staffCode);
            if (account != null) {
                row.setTargetUserId(account.getId());
                row.setOrgId(account.getOrgId());
                row.setFullName(account.getFullName());
            }
            try {
                requests.saveAndFlush(row);
            } catch (DataIntegrityViolationException raced) {
                // Another request for the same code won the race; the answer stays the same.
            }
        }
        return new RegistrationResult(ACCEPTED_MESSAGE);
    }
}
