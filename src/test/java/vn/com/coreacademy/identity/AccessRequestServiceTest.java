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
import vn.com.coreacademy.identity.entity.Role;
import vn.com.coreacademy.identity.entity.RoleAssignment;
import vn.com.coreacademy.identity.entity.UserAccount;
import vn.com.coreacademy.identity.enums.ScopeType;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.identity.repository.RoleRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.service.AccessRequestService;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import org.mockito.ArgumentCaptor;
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
    private final UserRepository users = mock(UserRepository.class);
    private final RoleRepository roles = mock(RoleRepository.class);
    private final RoleAssignmentRepository assignments = mock(RoleAssignmentRepository.class);
    private final IdentityWriteLock writeLock = mock(IdentityWriteLock.class);
    private final org.springframework.security.crypto.password.PasswordEncoder encoder =
        mock(org.springframework.security.crypto.password.PasswordEncoder.class);
    private final AccessRequestService service = new AccessRequestService(requests, access, organizations,
        new AccessRequestMapperImpl(), audit, users, roles, assignments, writeLock, encoder);

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

    // ---- approve -------------------------------------------------------------------------------

    private Organization unit(String type, String status) {
        Organization org = new Organization();
        org.setId(1L);
        org.setName("Ban A");
        org.setOrgType(type);
        org.setStatus(status);
        return org;
    }

    private Role learner() {
        Role role = mock(Role.class);
        when(role.getCode()).thenReturn("LEARNER");
        when(role.isActive()).thenReturn(true);
        return role;
    }

    private AccessRequest registration(long id) {
        AccessRequest row = request(id, AccessRequestKind.REGISTER, 1L, null);
        row.setStaffCode("000555");
        row.setFullName("Lê Thị Bình");
        row.setEmail("binh@example.test");
        return row;
    }

    @Test
    void approvesARegistrationByCreatingTheAccountWithTheChosenPasswordAndALearnerGrant() {
        when(access.boundary("000002")).thenReturn(UNIT);
        AccessRequest row = registration(5);
        when(requests.findById(5L)).thenReturn(Optional.of(row));
        when(organizations.findById(1L)).thenReturn(Optional.of(unit("UNIT", "active")));
        Role learnerRole = learner();
        when(assignments.save(any())).thenAnswer(call -> {
            RoleAssignment saved = call.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(saved, "id", 11L);
            return saved;
        });
        when(roles.findByCode("LEARNER")).thenReturn(Optional.of(learnerRole));

        AccessRequestView view = service.approve(5L, "  Đúng đối tượng ");

        ArgumentCaptor<UserAccount> user = ArgumentCaptor.forClass(UserAccount.class);
        verify(users, org.mockito.Mockito.atLeastOnce()).save(user.capture());
        UserAccount created = user.getAllValues().get(0);
        assertThat(created.getId()).isEqualTo("000555");
        assertThat(created.getUsername()).isEqualTo("000555");
        assertThat(created.getPasswordHash()).isEqualTo("$2a$10$secret-hash");
        assertThat(created.isMustChangePassword()).isFalse();
        assertThat(created.getOrgId()).isEqualTo(1L);
        ArgumentCaptor<RoleAssignment> grant = ArgumentCaptor.forClass(RoleAssignment.class);
        verify(assignments).save(grant.capture());
        assertThat(grant.getValue().getScopeType()).isEqualTo(ScopeType.OWN);
        assertThat(grant.getValue().getOrgId()).isNull();
        assertThat(row.getStatus()).isEqualTo(AccessRequestStatus.APPROVED);
        assertThat(row.getPasswordHash()).isNull();
        assertThat(row.getTargetUserId()).isEqualTo("000555");
        assertThat(row.getResolvedBy()).isEqualTo("000002");
        assertThat(row.getResolutionNote()).isEqualTo("Đúng đối tượng");
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.accountFound()).isTrue();
        verify(audit).record(eq("000002"), eq("ACCESS_REQUEST_APPROVE"), eq("ACCESS_REQUEST"), eq("5"), eq(1L), any());
        verify(audit).record(eq("000002"), eq("USER_CREATED"), eq("USER"), eq("000555"), eq(1L), any());
    }

    @Test
    void leavesTheRequestPendingWhenTheCodeWasTakenMeanwhile() {
        when(access.boundary("000002")).thenReturn(GLOBAL);
        AccessRequest row = registration(5);
        when(requests.findById(5L)).thenReturn(Optional.of(row));
        when(users.identifierExists("000555")).thenReturn(true);

        assertThatThrownBy(() -> service.approve(5L, null)).isInstanceOf(BusinessException.class);

        assertThat(row.getStatus()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(row.getPasswordHash()).isNotNull();
        verify(users, never()).save(any());
    }

    @Test
    void refusesToApproveAResolvedRequestAResetRequestOrOneOutsideTheScope() {
        when(access.boundary("000002")).thenReturn(UNIT);
        AccessRequest done = registration(6);
        done.setStatus(AccessRequestStatus.REJECTED);
        done.setPasswordHash(null);
        when(requests.findById(6L)).thenReturn(Optional.of(done));
        when(requests.findById(7L)).thenReturn(Optional.of(request(7, AccessRequestKind.PASSWORD_RESET, 1L, "000004")));
        AccessRequest outside = registration(8);
        outside.setOrgId(2L);
        when(requests.findById(8L)).thenReturn(Optional.of(outside));

        for (long id : new long[] {6L, 7L}) {
            assertThatThrownBy(() -> service.approve(id, null)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getResponseCode()).isEqualTo(ResponseCode.CONFLICT));
        }
        assertThatThrownBy(() -> service.approve(8L, null)).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getResponseCode()).isEqualTo(ResponseCode.RESOURCE_NOT_FOUND));
        verify(users, never()).save(any());
    }

    @Test
    void refusesWhenTheUnitIsNoLongerActive() {
        when(access.boundary("000002")).thenReturn(GLOBAL);
        when(requests.findById(5L)).thenReturn(Optional.of(registration(5)));
        when(organizations.findById(1L)).thenReturn(Optional.of(unit("UNIT", "inactive")));
        assertThatThrownBy(() -> service.approve(5L, null)).isInstanceOf(BusinessException.class);
        verify(users, never()).save(any());
    }

    @Test
    void rejectingARegistrationClearsItsPasswordHash() {
        when(access.boundary("000002")).thenReturn(GLOBAL);
        AccessRequest row = registration(5);
        when(requests.findById(5L)).thenReturn(Optional.of(row));
        when(organizations.findAllById(any())).thenReturn(List.of());
        service.reject(5L, null);
        assertThat(row.getPasswordHash()).isNull();
    }

    private AccessRequest passwordReset(long id, String target) {
        AccessRequest row = request(id, AccessRequestKind.PASSWORD_RESET, 1L, target);
        row.setPasswordHash(null);
        return row;
    }

    private UserAccount account(String id) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUsername(id);
        user.setOrgId(1L);
        user.setPasswordHash("old-hash");
        return user;
    }

    @Test
    void resetsThePasswordOnceWithAHashedPolicyValidTemporaryOneAndNeverAuditsIt() {
        when(access.boundary("000002")).thenReturn(UNIT);
        AccessRequest row = passwordReset(7, "000004");
        when(requests.findById(7L)).thenReturn(Optional.of(row));
        UserAccount user = account("000004");
        long before = user.getAuthorizationVersion();
        when(access.manageableUser("000002", "000004")).thenReturn(user);
        when(encoder.encode(any())).thenReturn("new-hash");

        var result = service.resetPassword(7L, " đã gọi lại ");

        ArgumentCaptor<String> encoded = ArgumentCaptor.forClass(String.class);
        verify(encoder).encode(encoded.capture());
        assertThat(result.temporaryPassword()).isEqualTo(encoded.getValue()).hasSize(12);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.isMustChangePassword()).isTrue();
        assertThat(user.getAuthorizationVersion()).isEqualTo(before + 1);
        assertThat(row.getStatus()).isEqualTo(AccessRequestStatus.APPROVED);
        assertThat(row.getResolutionNote()).isEqualTo("đã gọi lại");
        assertThat(result.request().status()).isEqualTo("APPROVED");
        assertThat(result.toString()).doesNotContain(result.temporaryPassword());
        verify(audit).record(eq("000002"), eq("USER_PASSWORD_RESET"), eq("USER"), eq("000004"), eq(1L),
            any());
        verify(audit).record(eq("000002"), eq("ACCESS_REQUEST_RESET_PASSWORD"), eq("ACCESS_REQUEST"), eq("7"),
            eq(1L), any());
    }

    @Test
    void generatesDistinctPasswordsFromTheUnambiguousAlphabet() {
        var seen = new java.util.HashSet<String>();
        for (int i = 0; i < 200; i++) {
            String password = AccessRequestService.generateTemporaryPassword();
            assertThat(password).matches("[A-HJ-NP-Za-km-z2-9]{12}");
            seen.add(password);
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    void refusesWithAStateConflictAndChangesNothingForEveryNonResettableRequest() {
        when(access.boundary("000002")).thenReturn(GLOBAL);
        AccessRequest resolved = passwordReset(1, "000004");
        resolved.setStatus(AccessRequestStatus.REJECTED);
        AccessRequest registration = registration(2);
        AccessRequest noTarget = passwordReset(3, null);
        AccessRequest sso = passwordReset(4, "000010");
        AccessRequest locked = passwordReset(5, "000011");
        for (AccessRequest row : List.of(resolved, registration, noTarget, sso, locked)) {
            when(requests.findById(row.getId())).thenReturn(Optional.of(row));
        }
        UserAccount ssoUser = account("000010");
        ssoUser.setAuthProvider(vn.com.coreacademy.identity.enums.AuthProvider.SSO);
        UserAccount lockedUser = account("000011");
        lockedUser.setStatus(vn.com.coreacademy.identity.enums.UserStatus.LOCKED);
        when(access.manageableUser("000002", "000010")).thenReturn(ssoUser);
        when(access.manageableUser("000002", "000011")).thenReturn(lockedUser);

        for (long id = 1; id <= 5; id++) {
            long requestId = id;
            assertThatThrownBy(() -> service.resetPassword(requestId, null))
                .isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getResponseCode()).isEqualTo(ResponseCode.CONFLICT));
        }
        assertThat(ssoUser.getPasswordHash()).isEqualTo("old-hash");
        assertThat(lockedUser.getPasswordHash()).isEqualTo("old-hash");
        verify(encoder, never()).encode(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void answersNotFoundForARequestOutsideTheBoundary() {
        when(access.boundary("000002")).thenReturn(UNIT);
        when(requests.findById(8L)).thenReturn(Optional.of(request(8, AccessRequestKind.PASSWORD_RESET, 9L, "000004")));
        assertThatThrownBy(() -> service.resetPassword(8L, null)).isInstanceOfSatisfying(BusinessException.class,
            e -> assertThat(e.getResponseCode()).isEqualTo(ResponseCode.RESOURCE_NOT_FOUND));
    }
}
