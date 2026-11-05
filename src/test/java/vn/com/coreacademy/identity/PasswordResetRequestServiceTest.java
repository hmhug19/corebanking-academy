package vn.com.coreacademy.identity;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import vn.com.coreacademy.identity.entity.AccessRequest;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.AccessRequestKind;
import vn.com.coreacademy.identity.enums.AccessRequestStatus;
import vn.com.coreacademy.identity.repository.AccessRequestRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.service.AccessRequestThrottle;
import vn.com.coreacademy.identity.service.PasswordResetRequestService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PasswordResetRequestServiceTest {
    private final AccessRequestRepository requests = mock(AccessRequestRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PasswordResetRequestService service =
        new PasswordResetRequestService(requests, users, new AccessRequestThrottle());

    private static UserAccount account() {
        UserAccount user = new UserAccount();
        user.setId("000004");
        user.setUsername("hocvien");
        user.setFullName("Học viên Demo");
        user.setOrgId(1L);
        return user;
    }

    @Test
    void storesTheMatchedAccountAndItsUnit() {
        when(users.findByIdentifier("000004")).thenReturn(List.of(account()));
        String message = service.request("10.0.0.1", " 000004 ").message();

        assertThat(message).isEqualTo(PasswordResetRequestService.ACCEPTED_MESSAGE);
        ArgumentCaptor<AccessRequest> saved = ArgumentCaptor.forClass(AccessRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        AccessRequest row = saved.getValue();
        assertThat(row.getKind()).isEqualTo(AccessRequestKind.PASSWORD_RESET);
        assertThat(row.getStatus()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(row.getStaffCode()).isEqualTo("000004");
        assertThat(row.getTargetUserId()).isEqualTo("000004");
        assertThat(row.getOrgId()).isEqualTo(1L);
        assertThat(row.getPasswordHash()).isNull();
    }

    @Test
    void storesAnUnknownCodeWithNoAccountAndNoUnit() {
        when(users.findByIdentifier("999999")).thenReturn(List.of());
        service.request("10.0.0.1", "999999");

        ArgumentCaptor<AccessRequest> saved = ArgumentCaptor.forClass(AccessRequest.class);
        verify(requests).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTargetUserId()).isNull();
        assertThat(saved.getValue().getOrgId()).isNull();
    }

    @Test
    void answersTheSameInEveryCase() {
        when(users.findByIdentifier("000004")).thenReturn(List.of(account()));
        String matched = service.request("10.0.0.1", "000004").message();
        String unknown = service.request("10.0.0.2", "999999").message();

        when(requests.existsByKindAndStatusAndStaffCodeIgnoreCase(AccessRequestKind.PASSWORD_RESET,
            AccessRequestStatus.PENDING, "000005")).thenReturn(true);
        String waiting = service.request("10.0.0.3", "000005").message();

        when(requests.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_access_requests_pending"));
        String raced = service.request("10.0.0.4", "000006").message();

        assertThat(List.of(unknown, waiting, raced)).allMatch(matched::equals);
    }

    @Test
    void createsNothingWhenARequestIsAlreadyWaiting() {
        when(requests.existsByKindAndStatusAndStaffCodeIgnoreCase(AccessRequestKind.PASSWORD_RESET,
            AccessRequestStatus.PENDING, "000004")).thenReturn(true);
        service.request("10.0.0.1", "000004");
        verify(requests, never()).saveAndFlush(any());
    }

    @Test
    void throttlesTheSixthCallFromOneIpWith429AndRetryAfter() {
        for (int i = 0; i < 5; i++) {
            service.request("10.0.0.9", "00070" + i);
        }
        assertThatThrownBy(() -> service.request("10.0.0.9", "000709"))
            .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(e.getHeaders().getFirst("Retry-After")).isNotNull();
            });
    }
}
