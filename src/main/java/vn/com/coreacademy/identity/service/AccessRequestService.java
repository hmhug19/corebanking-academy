package vn.com.coreacademy.identity.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.identity.constants.AccessRequestLimits;
import vn.com.coreacademy.identity.dto.AccessRequestView;
import vn.com.coreacademy.identity.entity.AccessRequest;
import vn.com.coreacademy.identity.enums.AccessRequestKind;
import vn.com.coreacademy.identity.enums.AccessRequestStatus;
import vn.com.coreacademy.identity.mapper.AccessRequestMapper;
import vn.com.coreacademy.identity.repository.AccessRequestRepository;
import vn.com.coreacademy.identity.service.IdentityAccessPolicy.Boundary;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.repository.OrganizationRepository;
import vn.com.coreacademy.security.SecurityContextUtils;

/**
 * The admin inbox of access requests. Scope comes from the same boundary the users list uses: a global admin
 * sees every request, a unit admin only those of their units, and a request with no unit is global-only.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AccessRequestService {
    /** A stand-in so {@code in :orgIds} is never empty for a global caller; no unit has this id. */
    private static final Set<Long> NO_ORG = Set.of(-1L);
    private static final Set<String> SORT_FIELDS = Set.of("id", "createTime", "staffCode");

    private final AccessRequestRepository requests;
    private final IdentityAccessPolicy access;
    private final OrganizationRepository organizations;
    private final AccessRequestMapper mapper;
    private final IdentityAuditService audit;

    public PageResponse<AccessRequestView> list(String kind, String status, String q, int page, int size,
                                                String sort) {
        Boundary boundary = access.boundary(SecurityContextUtils.getUserId());
        var pageable = PageQuery.of(page, size, sort, SORT_FIELDS);
        var result = requests.search(boundary.global(), boundary.global() || boundary.orgIds().isEmpty()
                ? NO_ORG : boundary.orgIds(),
            parse(AccessRequestKind.class, "kind", kind), parse(AccessRequestStatus.class, "status", status),
            likePattern(q), pageable);
        var orgNames = orgNames(result.getContent());
        return PageResponse.from(result.map(row -> mapper.toView(row, nameOf(orgNames, row.getOrgId()))));
    }

    /**
     * Rejects a pending request in scope. A request outside the scope answers not found, so its existence is
     * not revealed; one that is already resolved answers a state conflict.
     */
    @Transactional
    public AccessRequestView reject(Long requestId, String note) {
        String actorId = SecurityContextUtils.getUserId();
        Boundary boundary = access.boundary(actorId);
        String trimmed = note == null ? null : note.trim();
        if (trimmed != null && trimmed.length() > AccessRequestLimits.NOTE_MAX) {
            throw BusinessException.validation("note", "TOO_LONG", "Ghi chú tối đa 500 ký tự.");
        }

        AccessRequest request = requests.findById(requestId)
            .filter(row -> boundary.contains(row.getOrgId()))
            .orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        if (request.getStatus() != AccessRequestStatus.PENDING) {
            throw new BusinessException(ResponseCode.CONFLICT);
        }

        request.setStatus(AccessRequestStatus.REJECTED);
        request.setResolvedBy(actorId);
        request.setResolvedTime(Instant.now());
        request.setResolutionNote(trimmed == null || trimmed.isEmpty() ? null : trimmed);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("kind", request.getKind().name());
        changes.put("staffCode", request.getStaffCode());
        changes.put("status", request.getStatus().name());
        audit.record(actorId, "ACCESS_REQUEST_REJECT", "ACCESS_REQUEST", String.valueOf(request.getId()),
            request.getOrgId(), changes);

        return mapper.toView(request, nameOf(orgNames(List.of(request)), request.getOrgId()));
    }

    /** Map.of() refuses a null key, and a request may have no unit. */
    private static String nameOf(Map<Long, String> names, Long orgId) {
        return orgId == null ? null : names.get(orgId);
    }

    private Map<Long, String> orgNames(List<AccessRequest> rows) {
        Set<Long> ids = rows.stream().map(AccessRequest::getOrgId).filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return organizations.findAllById(ids).stream()
            .collect(Collectors.toMap(Organization::getId, Organization::getName, (a, b) -> a));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw BusinessException.validation(field, "INVALID_VALUE", field + " không hợp lệ.");
        }
    }

    /** A lower-cased "contains" pattern for LIKE with {@code \} as the escape, so the text stays literal. */
    private static String likePattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String text = q.trim();
        if (text.length() > AccessRequestLimits.SEARCH_MAX) {
            throw BusinessException.validation("q", "TOO_LONG", "q tối đa 100 ký tự.");
        }
        return "%" + text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
