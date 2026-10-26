package vn.com.coreacademy.organization.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
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
import vn.com.coreacademy.identity.repository.projection.TeamMemberPreview;
import vn.com.coreacademy.identity.service.IdentityAccessPolicy;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.learning.service.LearningReferenceService;
import vn.com.coreacademy.organization.dto.CreateTeamRequest;
import vn.com.coreacademy.organization.dto.TeamInfo;
import vn.com.coreacademy.organization.dto.TeamUpdateRequest;
import vn.com.coreacademy.organization.entity.Team;
import vn.com.coreacademy.organization.exception.OrganizationResponseCode;
import vn.com.coreacademy.organization.mapper.OrganizationMapper;
import vn.com.coreacademy.organization.repository.TeamRepository;
import vn.com.coreacademy.security.SecurityContextUtils;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TeamAdministrationService {
    private final TeamRepository teams;
    private final UserRepository users;
    private final RoleAssignmentRepository assignments;
    private final OrganizationAdministrationService organizations;
    private final IdentityAccessPolicy access;
    private final IdentityWriteLock writeLock;
    private final LearningReferenceService learningReferences;
    private final OrganizationMapper mapper;
    private final IdentityAuditService audit;

    /**
     * Trả các nhóm trực thuộc tổ chức được phép xem; tải số thành viên và tối đa 5 thành viên xem trước mỗi
     * nhóm theo lô. Chỉ tính thành viên có orgId khớp tổ chức thực tế của nhóm.
     */
    public PageResponse<TeamInfo> list(Long orgId, int page, int size, String sort) {
        String actorId = SecurityContextUtils.getUserId();
        organizations.visible(actorId, orgId);

        var result = teams.findByOrgId(orgId,
            PageQuery.of(page, size, sort, Set.of("id", "code", "name")));

        if (result.isEmpty()) {
            return PageResponse.from(result.map(team -> mapper.toInfo(team, 0, List.of())));
        }

        List<Long> ids = result.stream().map(Team::getId).toList();
        var counts = users.countMembersByTeamIds(ids).stream()
            .collect(Collectors.toMap(IdCount::getId, IdCount::getTotal));
        var previews = users.findMemberPreviews(ids).stream()
            .collect(Collectors.groupingBy(TeamMemberPreview::getTeamId,
                Collectors.mapping(u -> new TeamInfo.MemberPreview(u.getUserId(), u.getFullName()),
                    Collectors.toList())));

        return PageResponse.from(result.map(team -> mapper.toInfo(team, counts.getOrDefault(team.getId(), 0L),
            previews.getOrDefault(team.getId(), List.of()))));
    }

    /**
     * Tạo nhóm tại tổ chức đang hoạt động trong phạm vi quản lý và ghi audit cùng transaction. Mã nhóm nếu có
     * phải duy nhất trong tổ chức; không tự gán thành viên.
     */
    @Transactional
    public TeamInfo create(Long orgId, CreateTeamRequest request) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        organizations.visible(actorId, orgId);
        access.requireActiveOrg(orgId);
        uniqueCode(orgId, request.code(), -1L);

        Team team = new Team();
        team.setOrgId(orgId);
        team.setCode(request.code() == null ? null : request.code().trim());
        team.setName(TextValues.required(request.name(), "name"));
        team.setDescription(request.description());

        teams.save(team);

        audit.record(actorId, "TEAM_CREATED", "TEAM", team.getId().toString(), orgId,
            Map.of("name", team.getName()));

        return info(team);
    }

    /**
     * Sửa thông tin nhóm trong phạm vi quản lý và ghi audit cùng transaction. Giữ nguyên tổ chức và trạng
     * thái; chỉ cập nhật các trường được gửi trong PATCH.
     */
    @Transactional
    public TeamInfo update(Long teamId, TeamUpdateRequest request) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        Team team = visible(actorId, teamId);
        access.requireActiveOrg(team.getOrgId());
        if (request.getSuppliedFields().isEmpty()) {
            throw BusinessException.validation("", "EMPTY_UPDATE", "Vui lòng cung cấp thông tin cần cập nhật.");
        }

        if (request.has("code")) {
            uniqueCode(team.getOrgId(), request.getCode(), teamId);
            team.setCode(request.getCode() == null ? null : request.getCode().trim());
        }
        if (request.has("name")) {
            team.setName(TextValues.required(request.getName(), "name"));
        }
        if (request.has("description")) {
            team.setDescription(request.getDescription());
        }

        teams.save(team);

        audit.record(actorId, "TEAM_UPDATED", "TEAM", teamId.toString(), team.getOrgId(),
            Map.of("fields", request.getSuppliedFields()));

        return info(team);
    }

    /**
     * Xóa nhóm và ghi audit cùng transaction khi không còn thành viên hoặc phân quyền tham chiếu. Phân quyền
     * đã thu hồi vẫn là tham chiếu lịch sử; không xóa cascade để vượt kiểm tra này.
     */
    @Transactional
    public void delete(Long teamId) {
        String actorId = SecurityContextUtils.getUserId();
        writeLock.acquire();
        Team team = visible(actorId, teamId);
        access.requireActiveOrg(team.getOrgId());
        // Không có FK: giữ nhóm đã được Learning tham chiếu, kể cả snapshot lịch sử đã hoàn thành/thu hồi.
        if (users.existsByTeamId(teamId) || assignments.existsByTeamId(teamId)
            || learningReferences.teamInUse(teamId)) {
            throw new BusinessException(OrganizationResponseCode.TEAM_IN_USE);
        }

        teams.delete(team);

        audit.record(actorId, "TEAM_DELETED", "TEAM", teamId.toString(), team.getOrgId(),
            Map.of("name", team.getName()));
    }

    private Team visible(String actorId, Long teamId) {
        Team team = teams.findById(teamId).orElseThrow(() -> new BusinessException(ResponseCode.RESOURCE_NOT_FOUND));
        organizations.visible(actorId, team.getOrgId());
        return team;
    }

    private TeamInfo info(Team team) {
        var members = users.findTop5ByTeamIdAndOrgIdOrderById(team.getId(), team.getOrgId()).stream()
            .map(u -> new TeamInfo.MemberPreview(u.getId(), u.getFullName())).toList();
        return mapper.toInfo(team, users.countByTeamIdAndOrgId(team.getId(), team.getOrgId()), members);
    }

    private void uniqueCode(Long orgId, String code, Long exceptId) {
        if (code != null && !code.isBlank()
            && teams.existsByOrgIdAndCodeIgnoreCaseAndIdNot(orgId, code.trim(), exceptId)) {
            throw BusinessException.field(OrganizationResponseCode.TEAM_CODE_EXISTS, "code", "ALREADY_EXISTS",
                "Mã nhóm đã được sử dụng trong tổ chức này.");
        }
    }
}
