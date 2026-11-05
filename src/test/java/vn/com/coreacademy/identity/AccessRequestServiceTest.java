package vn.com.coreacademy.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.dto.AccessRequestView;
import vn.com.coreacademy.identity.entity.AccessRequest;
import vn.com.coreacademy.identity.enums.AccessRequestKind;
import vn.com.coreacademy.identity.enums.AccessRequestStatus;
import vn.com.coreacademy.identity.mapper.AccessRequestMapperImpl;
import vn.com.coreacademy.identity.repository.AccessRequestRepository;
import vn.com.coreacademy.identity.service.AccessRequestService;
import vn.com.coreacademy.identity.service.IdentityAccessPolicy;
import vn.com.coreacademy.identity.service.IdentityAccessPolicy.Boundary;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.repository.OrganizationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessRequestServiceTest {
    private static final Boundary GLOBAL = new Boundary(true, false, Set.of());
    private static final Boundary UNIT = new Boundary(false, false, Set.of(1L));

    private final AccessRequestRepository requests = mock(AccessRequestRepository.class);
    private final IdentityAccessPolicy access = mock(IdentityAccessPolicy.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final IdentityAuditService audit = mock(IdentityAuditService.class);
    private final AccessRequestService service =
        new AccessRequestService(requests, access, organizations, new AccessRequestMapperImpl(), audit);

    @BeforeEach
    void signIn() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("000002").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static AccessRequest request(long id, AccessRequestKind kind, Long orgId, String targetUserId) {
        AccessRequest row = new AccessRequest();
        row.setId(id);
        row.setKind(kind);
        row.setStatus(AccessRequestStatus.PENDING);
        row.setStaffCode("000101");
        row.setOrgId(orgId);
        row.setTargetUserId(targetUserId);
        row.setPasswordHash("$2a$10$secret-hash");
        return row;
    }

    @Test
    void listsPageWithOrgNameAndAccountFoundAndNoPasswordHash() {
        when(access.boundary("000002")).thenReturn(GLOBAL);
        AccessRequest reset = request(1, AccessRequestKind.PASSWORD_RESET, 1L, null);
        Organization org = mock(Organization.class);
        when(org.getId()).thenReturn(1L);
        when(org.getName()).thenReturn("Ban Core Banking");
        when(organizations.findAllById(any())).thenReturn(List.of(org));
        when(requests.search(anyBoolean(), anyCollection(), isNull(), eq(AccessRequestStatus.PENDING), isNull(),
            any())).thenReturn(new PageImpl<>(List.of(reset), PageRequest.of(0, 10), 1));

        var page = service.list(null, "PENDING", null, 0, 10, "createTime,desc");

        assertThat(page.totalElements()).isEqualTo(1);
        AccessRequestView view = page.items().get(0);
        assertThat(view.orgName()).isEqualTo("Ban Core Banking");
        assertThat(view.accountFound()).isFalse();
        assertThat(view.kind()).isEqualTo("PASSWORD_RESET");
        assertThat(view.toString()).doesNotContain("secret-hash");
        assertThat(AccessRequestView.class.getRecordComponents())
            .extracting(component -> component.getName().toLowerCase()).noneMatch(name -> name.contains("password"));
    }

    @Test
    void refusesUnknownKindStatusAndLongSearch() {
        when(access.boundary("000002")).thenReturn(GLOBAL);
        assertThatThrownBy(() -> service.list("OTHER", null, null, 0, 10, "createTime,desc"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(null, "CANCELLED", null, 0, 10, "createTime,desc"))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list(null, null, "x".repeat(101), 0, 10, "createTime,desc"))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsAPendingRequestInScopeAndRecordsWhoAndWhen() {
        when(access.boundary("000002")).thenReturn(UNIT);
        AccessRequest row = request(7, AccessRequestKind.REGISTER, 1L, null);
        when(requests.findById(7L)).thenReturn(Optional.of(row));
        when(organizations.findAllById(any())).thenReturn(List.of());

        AccessRequestView view = service.reject(7L, "  Không đúng đối tượng  ");

        assertThat(row.getStatus()).isEqualTo(AccessRequestStatus.REJECTED);
        assertThat(row.getResolvedBy()).isEqualTo("000002");
        assertThat(row.getResolvedTime()).isBeforeOrEqualTo(Instant.now());
        assertThat(row.getResolutionNote()).isEqualTo("Không đúng đối tượng");
        assertThat(view.status()).isEqualTo("REJECTED");
        verify(audit).record(eq("000002"), eq("ACCESS_REQUEST_REJECT"), eq("ACCESS_REQUEST"), eq("7"), eq(1L), any());
    }

    @Test
    void keepsAnEmptyNoteAsNull() {
        when(access.boundary("000002")).thenReturn(GLOBAL);
        AccessRequest row = request(8, AccessRequestKind.REGISTER, null, null);
        when(requests.findById(8L)).thenReturn(Optional.of(row));
        when(organizations.findAllById(any())).thenReturn(List.of());
        service.reject(8L, "   ");
        assertThat(row.getResolutionNote()).isNull();
    }

    @Test
    void answersNotFoundForARequestOutsideTheUnitAndForANullUnitRow() {
        when(access.boundary("000002")).thenReturn(UNIT);
        when(requests.findById(1L)).thenReturn(Optional.of(request(1, AccessRequestKind.REGISTER, 2L, null)));
        when(requests.findById(2L)).thenReturn(Optional.of(request(2, AccessRequestKind.PASSWORD_RESET, null, null)));
        for (long id : new long[] {1L, 2L}) {
            assertThatThrownBy(() -> service.reject(id, null))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getResponseCode()).isEqualTo(ResponseCode.RESOURCE_NOT_FOUND));
        }
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void answersAStateConflictWhenTheRequestIsAlreadyResolved() {
        when(access.boundary("000002")).thenReturn(GLOBAL);
        AccessRequest row = request(9, AccessRequestKind.REGISTER, 1L, null);
        row.setStatus(AccessRequestStatus.APPROVED);
        when(requests.findById(9L)).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.reject(9L, null))
            .isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getResponseCode()).isEqualTo(ResponseCode.CONFLICT));
        assertThat(row.getStatus()).isEqualTo(AccessRequestStatus.APPROVED);
    }

    @Test
    void refusesANoteOver500Characters() {
        when(access.boundary("000002")).thenReturn(GLOBAL);
        assertThatThrownBy(() -> service.reject(7L, "x".repeat(501))).isInstanceOf(BusinessException.class);
    }

    @Test
    void entityToStringNeverPrintsTheHash() {
        assertThat(request(1, AccessRequestKind.REGISTER, 1L, null).toString()).doesNotContain("secret-hash");
    }
}
