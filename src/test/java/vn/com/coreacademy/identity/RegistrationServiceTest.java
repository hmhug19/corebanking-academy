package vn.com.coreacademy.identity;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.dto.RegisterRequest;
import vn.com.coreacademy.identity.dto.RegistrationResult;
import vn.com.coreacademy.identity.entity.AccessRequest;
import vn.com.coreacademy.identity.enums.AccessRequestKind;
import vn.com.coreacademy.identity.enums.AccessRequestStatus;
import vn.com.coreacademy.identity.repository.AccessRequestRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.service.AccessRequestThrottle;
import vn.com.coreacademy.identity.service.RegistrationService;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.repository.OrganizationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationServiceTest {
    private final AccessRequestRepository requests = mock(AccessRequestRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final PasswordEncoder passwords = mock(PasswordEncoder.class);
    private final AccessRequestThrottle throttle = new AccessRequestThrottle();
    private final RegistrationService service =
        new RegistrationService(requests, users, organizations, passwords, throttle);

    private static Organization org(long id, String type, String status) {
        Organization org = new Organization();
        org.setId(id);
        org.setOrgType(type);
        org.setStatus(status);
        org.setName("Ban " + id);
        return org;
    }

    private static RegisterRequest form(String code) {
        return new RegisterRequest(code, " Lê Thị Bình ", "binh@example.test", 1L, "Matkhau@1");
    }

    private void unitExists() {
        when(organizations.findById(1L)).thenReturn(Optional.of(org(1, "UNIT", "active")));
        when(passwords.encode("Matkhau@1")).thenReturn("$2a$hash");
    }

    @Test
    void listsOnlyActiveUnitsAsIdAndName() {
        when(organizations.findByOrgTypeAndStatusOrderBySortNoAscNameAsc("UNIT", "active"))
            .thenReturn(List.of(org(1, "UNIT", "active")));
        assertThat(service.units()).singleElement().satisfies(unit -> {
            assertThat(unit.orgId()).isEqualTo(1L);
            assertThat(unit.name()).isEqualTo("Ban 1");
        });
    }

    @Test
    void storesAPendingRequestWithAHashedPasswordAndTheUnit() {
        unitExists();
        RegistrationResult result = service.register("10.0.0.1", form("000555"));

        assertThat(result.message()).isEqualTo(RegistrationService.ACCEPTED_MESSAGE);
        ArgumentCaptor<AccessRequest> saved = ArgumentCaptor.forClass(AccessRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        AccessRequest row = saved.getValue();
        assertThat(row.getKind()).isEqualTo(AccessRequestKind.REGISTER);
        assertThat(row.getStatus()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(row.getStaffCode()).isEqualTo("000555");
        assertThat(row.getFullName()).isEqualTo("Lê Thị Bình");
        assertThat(row.getOrgId()).isEqualTo(1L);
        assertThat(row.getPasswordHash()).isEqualTo("$2a$hash").isNotEqualTo("Matkhau@1");
        assertThat(row.toString()).doesNotContain("hash");
    }

    @Test
    void answersTheSameWhenACodeHasAnAccountOrAWaitingRequestAndCreatesNothing() {
        unitExists();
        RegistrationResult fresh = service.register("10.0.0.1", form("000555"));

        when(users.identifierExists("000556")).thenReturn(true);
        RegistrationResult account = service.register("10.0.0.2", form("000556"));

        when(requests.existsByKindAndStatusAndStaffCodeIgnoreCase(AccessRequestKind.REGISTER,
            AccessRequestStatus.PENDING, "000557")).thenReturn(true);
        RegistrationResult waiting = service.register("10.0.0.3", form("000557"));

        assertThat(account).isEqualTo(fresh);
        assertThat(waiting).isEqualTo(fresh);
        verify(requests).saveAndFlush(any());
    }

    @Test
    void answersTheSameWhenAnotherRequestWinsTheRace() {
        unitExists();
        when(requests.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_access_requests_pending"));
        assertThat(service.register("10.0.0.1", form("000555")).message())
            .isEqualTo(RegistrationService.ACCEPTED_MESSAGE);
    }

    @Test
    void refusesAnUnknownInactiveOrDepartmentUnit() {
        when(passwords.encode("Matkhau@1")).thenReturn("$2a$hash");
        when(organizations.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.register("10.0.0.1", form("000555")))
            .isInstanceOf(BusinessException.class);

        when(organizations.findById(1L)).thenReturn(Optional.of(org(1, "DEPARTMENT", "active")));
        assertThatThrownBy(() -> service.register("10.0.0.2", form("000556")))
            .isInstanceOf(BusinessException.class);

        when(organizations.findById(1L)).thenReturn(Optional.of(org(1, "UNIT", "inactive")));
        assertThatThrownBy(() -> service.register("10.0.0.3", form("000557")))
            .isInstanceOf(BusinessException.class);
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void refusesAWeakPassword() {
        unitExists();
        RegisterRequest weak = new RegisterRequest("000555", "Bình", "binh@example.test", 1L, "      ");
        assertThatThrownBy(() -> service.register("10.0.0.1", weak)).isInstanceOf(BusinessException.class);
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void throttlesTheSixthCallFromOneIpWith429AndRetryAfter() {
        unitExists();
        for (int i = 0; i < 5; i++) {
            service.register("10.0.0.9", form("00060" + i));
        }
        assertThatThrownBy(() -> service.register("10.0.0.9", form("000609")))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(e.getHeaders().getFirst("Retry-After")).isNotNull();
            });
    }

    @Test
    void neverPrintsThePassword() {
        assertThat(form("000555").toString()).doesNotContain("Matkhau").doesNotContain("000555");
    }
}
