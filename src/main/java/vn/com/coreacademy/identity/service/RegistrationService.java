package vn.com.coreacademy.identity.service;

import java.util.List;
import java.util.Locale;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.dto.RegisterRequest;
import vn.com.coreacademy.identity.dto.RegistrationResult;
import vn.com.coreacademy.identity.dto.RegistrationUnit;
import vn.com.coreacademy.identity.entity.AccessRequest;
import vn.com.coreacademy.identity.enums.AccessRequestKind;
import vn.com.coreacademy.identity.enums.AccessRequestStatus;
import vn.com.coreacademy.identity.repository.AccessRequestRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.security.PasswordPolicy;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.repository.OrganizationRepository;

/**
 * The public side of registration. A request is answered the same way whether the staff code is new, already
 * waiting or already an account, so the endpoint cannot be used to find accounts. Not transactional on purpose:
 * a lost race on the one-pending-per-code index is caught here and answered like any other duplicate.
 */
@Service
@RequiredArgsConstructor
public class RegistrationService {
    public static final String ACCEPTED_MESSAGE = "Yêu cầu đã được gửi. Quản trị viên sẽ xem xét; "
        + "bạn sẽ đăng nhập được sau khi yêu cầu được duyệt.";

    private final AccessRequestRepository requests;
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final PasswordEncoder passwords;
    private final AccessRequestThrottle throttle;

    /** Active units only, with no code, tree or department. */
    public List<RegistrationUnit> units() {
        return organizations.findByOrgTypeAndStatusOrderBySortNoAscNameAsc("UNIT", "active").stream()
            .map(unit -> new RegistrationUnit(unit.getId(), unit.getName())).toList();
    }

    public RegistrationResult register(String ip, RegisterRequest in) {
        String staffCode = in.staffCode().trim();
        throttle.check(ip, AccessRequestKind.REGISTER, staffCode);
        PasswordPolicy.validate(in.password(), "password");
        Organization unit = organizations.findById(in.orgId()).orElse(null);
        if (unit == null || !"UNIT".equals(unit.getOrgType()) || !"active".equals(unit.getStatus())) {
            throw BusinessException.validation("orgId", "INVALID_UNIT", "Đơn vị không hợp lệ.");
        }

        String code = staffCode.toLowerCase(Locale.ROOT);
        boolean taken = users.identifierExists(code)
            || requests.existsByKindAndStatusAndStaffCodeIgnoreCase(AccessRequestKind.REGISTER,
                AccessRequestStatus.PENDING, staffCode);
        if (!taken) {
            AccessRequest row = new AccessRequest();
            row.setKind(AccessRequestKind.REGISTER);
            row.setStaffCode(staffCode);
            row.setFullName(in.fullName().trim());
            row.setEmail(in.email().trim());
            row.setOrgId(unit.getId());
            row.setPasswordHash(passwords.encode(in.password()));
            try {
                requests.saveAndFlush(row);
            } catch (DataIntegrityViolationException raced) {
                // Another request for the same code won the race; the answer stays the same.
            }
        }
        return new RegistrationResult(ACCEPTED_MESSAGE);
    }
}
