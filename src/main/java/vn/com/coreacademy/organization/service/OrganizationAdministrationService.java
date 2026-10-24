package vn.com.coreacademy.organization.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.common.persistence.IdCount;
import vn.com.coreacademy.common.validation.TextValues;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.identity.repository.RoleAssignmentRepository;
import vn.com.coreacademy.identity.repository.UserRepository;
import vn.com.coreacademy.identity.service.IdentityAccessPolicy;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.organization.dto.CreateOrganizationRequest;
import vn.com.coreacademy.organization.dto.OrganizationDetail;
import vn.com.coreacademy.organization.dto.OrganizationInfo;
import vn.com.coreacademy.organization.dto.OrganizationTreeNode;
import vn.com.coreacademy.organization.dto.OrganizationUpdateRequest;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.exception.OrganizationResponseCode;
import vn.com.coreacademy.organization.mapper.OrganizationMapper;
import vn.com.coreacademy.organization.repository.OrganizationRepository;
import vn.com.coreacademy.organization.repository.TeamRepository;
import vn.com.coreacademy.organization.repository.custom.OrganizationRepositoryCustom.OrganizationSearch;
import vn.com.coreacademy.security.SecurityContextUtils;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrganizationAdministrationService {
    private final OrganizationRepository organizations;
    private final TeamRepository teams;
    private final UserRepository users;
    private final RoleAssignmentRepository assignments;
    private final OrganizationMapper mapper;
    private final IdentityAccessPolicy access;
    private final IdentityWriteLock writeLock;
    private final IdentityAuditService audit;

    /**
     * Tra cứu tổ chức trong phạm vi quản lý với phân trang tại database. Nút gốc trong scope có thể có cha
     * ngoài scope; số thành viên/nhóm là số trực thuộc, được tổng hợp theo lô cho trang kết quả.
     */
    public PageResponse<OrganizationInfo> list(String q, Long parentId, boolean rootOnly, String orgType, String status,
                                               int page, int size, String sort) {
        String actorId = SecurityContextUtils.getUserId();
        var boundary = access.boundary(actorId);
        if (rootOnly && parentId != null) {
            throw BusinessException.validation("rootOnly", "CONFLICTING_FIELDS",
                "Không gửi parentId khi rootOnly=true.");
        }

        if (parentId != null) {
            access.requireOrg(boundary, parentId);
        }

        if (orgType != null && !Set.of("UNIT", "DEPARTMENT").contains(orgType)) {
            throw BusinessException.validation("orgType", "INVALID_VALUE",
                "Loại tổ chức phải là UNIT hoặc DEPARTMENT.");
        }
        if (status != null && !Set.of("active", "inactive").contains(status)) {
            throw BusinessException.validation("status", "INVALID_VALUE",
                "Trạng thái tổ chức phải là active hoặc inactive.");
        }

        var filter = new OrganizationSearch(q, parentId, rootOnly, orgType, status,
            boundary.global(), boundary.orgIds());
        var result = organizations.searchOrganizations(filter,
            PageQuery.of(page, size, sort, Set.of("id", "code", "name", "sortNo")));

        if (result.isEmpty()) {
            return PageResponse.from(result.map(org -> mapper.toInfo(org, 0, 0)));
        }

        List<Long> ids = result.stream().map(Organization::getId).toList();
        var memberCounts = users.countMembersByOrgIds(ids).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getTotal));
        var teamCounts = teams.countTeamsByOrgIds(ids).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getTotal));

        return PageResponse.from(result.map(org -> mapper.toInfo(org,
            memberCounts.getOrDefault(org.getId(), 0L), teamCounts.getOrDefault(org.getId(), 0L))));
    }

    /**
     * Tải một cấp cây theo scope, không đệ quy toàn bộ cây hoặc truy vấn con từng node. Không có parentId
     * trả các gốc trong phạm vi; hasChildren được kiểm tra theo lô và cùng phạm vi quản lý.
     */
    public PageResponse<OrganizationTreeNode> tree(Long parentId, int page, int size) {
        var boundary = access.boundary(SecurityContextUtils.getUserId());
        if (parentId != null && (!boundary.contains(parentId) || !organizations.existsById(parentId))) {
            throw new BusinessException(ResponseCode.RESOURCE_NOT_FOUND);
        }

        var filter = new OrganizationSearch(null, parentId, parentId == null, null, null,
            boundary.global(), boundary.orgIds());
        var result = organizations.searchOrganizations(filter,
            PageQuery.of(page, size, "sortNo,asc", Set.of("sortNo")));
        Set<Long> expandableIds;
        if (result.isEmpty()) {
            expandableIds = Set.of();
        } else {
            List<Long> ids = result.stream().map(Organization::getId).toList();
            expandableIds = Set.copyOf(boundary.global()
                ? organizations.findParentIdsWithChildren(ids)
                : organizations.findParentIdsWithVisibleChildren(ids, boundary.orgIds()));
        }

        return PageResponse.from(result.map(org -> new OrganizationTreeNode(org.getId(), org.getParentId(),
            org.getOrgType(), org.getCode(), org.getName(), org.getStatus(), org.getSortNo(),
            expandableIds.contains(org.getId()))));
    }

    /**
     * Trả tổ chức được phép xem và tối đa 20 quản trị viên ADMIN ORG gán trực tiếp. Không cộng quyền quản trị
     * kế thừa hoặc quản trị viên GLOBAL vào danh sách tóm tắt.
     */
    public OrganizationDetail get(Long orgId) {
        String actorId = SecurityContextUtils.getUserId();
        Organization org = visible(actorId, orgId);
        // Chỉ tóm tắt quyền gán trực tiếp tại node, không cộng quyền kế thừa từ cấp trên.
        List<OrganizationDetail.Administrator> administrators = assignments
            .findAdministratorsAtOrg(orgId, PageRequest.of(0, 20)).stream()
            .map(g -> new OrganizationDetail.Administrator(g.getUser().getId(), g.getUser().getFullName(),
                g.getRole().getCode(), g.getOrgId())).toList();
        return new OrganizationDetail(info(org), administrators);
    }

    /**
     * Tạo tổ chức và ghi audit cùng transaction, chỉ dành cho quản trị viên toàn hệ thống. UNIT có thể là gốc
     * hoặc dưới UNIT; DEPARTMENT phải có cha UNIT đang hoạt động.
     */
    @Transactional
    public OrganizationInfo create(CreateOrganizationRequest request) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        access.requireGlobal(actorId);
        if (request.parentId() == null && !"UNIT".equals(request.orgType())) {
            throw new BusinessException(OrganizationResponseCode.HIERARCHY_INVALID);
        }
        if (request.parentId() != null) {
            access.requireActiveOrg(request.parentId(), "parentId");
            Organization parent = organizations.findById(request.parentId()).orElseThrow();
            if ("DEPARTMENT".equals(parent.getOrgType())) {
                throw new BusinessException(OrganizationResponseCode.HIERARCHY_INVALID);
            }
        }

        String code = TextValues.required(request.code(), "code");
        uniqueCode(code, -1L);

        Organization org = new Organization();
        org.setParentId(request.parentId());
        org.setOrgType(request.orgType());
        org.setCode(code);
        org.setName(TextValues.required(request.name(), "name"));
        org.setSortNo(request.sortNo());

        organizations.save(org);

        audit.record(actorId, "ORG_CREATED", "ORG", org.getId().toString(), org.getId(),
            Map.of("code", org.getCode(), "orgType", org.getOrgType()));

        return info(org);
    }

    /**
     * Cập nhật tổ chức và audit cùng transaction; đổi trạng thái yêu cầu quyền quản trị toàn hệ thống. Nhánh
     * đổi trạng thái cho phép kích hoạt lại node inactive; không thay đổi parentId hoặc orgType.
     */
    @Transactional
    public OrganizationInfo update(Long orgId, OrganizationUpdateRequest request) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        Organization org = visible(actorId, orgId);
        if (request.getSuppliedFields().isEmpty()) {
            throw BusinessException.validation("", "EMPTY_UPDATE", "Vui lòng cung cấp thông tin cần cập nhật.");
        }
        if (request.has("status")) {
            access.requireGlobal(actorId);
            if (request.getStatus() == null) {
                throw BusinessException.validation("status", "REQUIRED", "Không được xóa trạng thái tổ chức.");
            }
        } else {
            access.requireActiveOrg(orgId);
        }

        if (request.has("code")) {
            String code = TextValues.required(request.getCode(), "code");
            uniqueCode(code, orgId);
            org.setCode(code);
        }
        if (request.has("name")) {
            org.setName(TextValues.required(request.getName(), "name"));
        }
        if (request.has("sortNo")) {
            if (request.getSortNo() == null) {
                throw BusinessException.validation("sortNo", "REQUIRED", "Không được xóa thứ tự hiển thị.");
            }
            org.setSortNo(request.getSortNo());
        }
        if (request.has("status")) {
            org.setStatus(request.getStatus());
        }

        organizations.save(org);

        audit.record(actorId, "ORG_UPDATED", "ORG", orgId.toString(), orgId,
            Map.of("fields", request.getSuppliedFields(), "status", org.getStatus()));

        return info(org);
    }

    /**
     * Kiểm tra phạm vi trước khi trả tổ chức; dùng cùng lỗi 404 cho node thiếu hoặc ngoài scope để không tiết
     * lộ dữ liệu qua URL trực tiếp.
     */
    public Organization visible(String actorId, Long orgId) {
        var boundary = access.boundary(actorId);
        if (!boundary.contains(orgId)) {
            throw new BusinessException(ResponseCode.RESOURCE_NOT_FOUND);
        }
        return organizations.findById(orgId).orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
    }

    private OrganizationInfo info(Organization org) {
        return mapper.toInfo(org, users.countByOrgId(org.getId()), teams.countByOrgId(org.getId()));
    }

    private void uniqueCode(String code, Long exceptId) {
        if (organizations.existsByCodeIgnoreCaseAndIdNot(code, exceptId)) {
            throw BusinessException.field(OrganizationResponseCode.ORG_CODE_EXISTS, "code", "ALREADY_EXISTS",
                "Mã tổ chức đã được sử dụng.");
        }
    }
}
