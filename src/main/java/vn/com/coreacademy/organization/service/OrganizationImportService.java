package vn.com.coreacademy.organization.service;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.validation.Validator;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.FieldViolation;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.validation.ValidationErrors;
import vn.com.coreacademy.identity.service.IdentityAccessPolicy;
import vn.com.coreacademy.identity.service.IdentityWriteLock;
import vn.com.coreacademy.organization.dto.CreateOrganizationRequest;
import vn.com.coreacademy.organization.dto.CreateTeamRequest;
import vn.com.coreacademy.organization.dto.OrganizationImportResult;
import vn.com.coreacademy.organization.entity.Organization;
import vn.com.coreacademy.organization.entity.Team;
import vn.com.coreacademy.organization.exception.OrganizationResponseCode;
import vn.com.coreacademy.organization.importing.OrganizationImportWorkbook;
import vn.com.coreacademy.organization.importing.OrganizationImportWorkbook.OrgRow;
import vn.com.coreacademy.organization.importing.OrganizationImportWorkbook.Parsed;
import vn.com.coreacademy.organization.importing.OrganizationImportWorkbook.TeamRow;
import vn.com.coreacademy.organization.repository.OrganizationRepository;
import vn.com.coreacademy.organization.repository.TeamRepository;
import vn.com.coreacademy.security.SecurityContextUtils;

/** Import create-only, dùng cùng scope, constraint DTO, write lock và audit với quản trị tổ chức. */
@Service
@RequiredArgsConstructor
public class OrganizationImportService {
    private final OrganizationImportWorkbook workbook;
    private final OrganizationRepository organizations;
    private final TeamRepository teams;
    private final IdentityAccessPolicy access;
    private final IdentityWriteLock lock;
    private final IdentityAuditService audit;
    private final Validator validator;
    private final PlatformTransactionManager transactionManager;

    private record TeamKey(String orgCode, String code) {
    }

    private record Plan(List<OrgRow> orderedOrganizations, Map<String, Organization> references) {
    }

    public byte[] template() throws IOException {
        access.boundary(SecurityContextUtils.getUserId());
        return workbook.template();
    }

    /**
     * Parse file ngoài transaction/lock. dryRun không ghi dữ liệu; nhập thật khóa và kiểm tra lại toàn bộ
     * tham chiếu/trùng/scope trước khi ghi. Bất kỳ lỗi persistence/audit nào đều rollback cả lô.
     */
    public OrganizationImportResult importFile(MultipartFile file, boolean dryRun) throws IOException {
        access.boundary(SecurityContextUtils.getUserId());
        Parsed parsed = workbook.read(file);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(dryRun);
        return transaction.execute(status -> {
            if (!dryRun) {
                lock.acquire();
            }
            String actorId = SecurityContextUtils.getUserId();
            var boundary = access.boundary(actorId);
            if (!parsed.organizations().isEmpty() && !boundary.global()) {
                throw new AccessDeniedException("Only global administrators can import organizations");
            }
            Plan plan = validate(parsed, boundary);
            if (!dryRun) {
                persist(parsed, plan, actorId);
            }
            return new OrganizationImportResult(dryRun, parsed.organizations().size(), parsed.teams().size(),
                dryRun ? 0 : parsed.organizations().size(), dryRun ? 0 : parsed.teams().size());
        });
    }

    private Plan validate(Parsed parsed, IdentityAccessPolicy.Boundary boundary) {
        List<FieldViolation> errors = new ArrayList<>(parsed.errors());
        Map<String, OrgRow> incoming = new LinkedHashMap<>();
        for (OrgRow row : parsed.organizations()) {
            String prefix = "Organizations[" + row.row() + "]";
            validateDto(new CreateOrganizationRequest(row.orgType(), null, row.code(), row.name(), row.sortNo()),
                prefix, errors);
            if (row.parentCode().length() > 50) {
                issue(errors, prefix + ".parentCode", "INVALID_LENGTH", "Mã tổ chức cha tối đa 50 ký tự.");
            }
            if (!row.code().isBlank()) {
                OrgRow previous = incoming.putIfAbsent(normalize(row.code()), row);
                if (previous != null) {
                    issue(errors, prefix + ".code", "DUPLICATE_IN_FILE", "Mã tổ chức bị lặp trong tệp.");
                    issue(errors, "Organizations[" + previous.row() + "].code", "DUPLICATE_IN_FILE",
                        "Mã tổ chức bị lặp trong tệp.");
                }
            }
        }

        Set<String> codes = Stream.concat(parsed.organizations().stream()
            .flatMap(row -> Stream.of(row.code(), row.parentCode())), parsed.teams().stream().map(TeamRow::orgCode))
            .filter(code -> !code.isBlank() && code.length() <= 50).map(this::normalize).collect(Collectors.toSet());
        Map<String, Organization> existing = codes.isEmpty() ? Map.of() : organizations.findByNormalizedCodes(codes)
            .stream().collect(Collectors.toMap(org -> normalize(org.getCode()), org -> org));
        List<Long> referenceIds = existing.values().stream().filter(org -> boundary.contains(org.getId()))
            .map(Organization::getId).distinct().toList();
        Set<Long> activeReferences = referenceIds.isEmpty() ? Set.of()
            : Set.copyOf(organizations.findIdsWithActiveAncestry(referenceIds));

        for (OrgRow row : parsed.organizations()) {
            String prefix = "Organizations[" + row.row() + "]";
            if (existing.containsKey(normalize(row.code()))) {
                issue(errors, prefix + ".code", "ALREADY_EXISTS", "Mã tổ chức đã được sử dụng.");
            }
            if (row.parentCode().isBlank()) {
                if ("DEPARTMENT".equals(row.orgType())) {
                    issue(errors, prefix + ".parentCode", "REQUIRED", "Phòng phải có mã đơn vị cha.");
                }
                continue;
            }
            OrgRow newParent = incoming.get(normalize(row.parentCode()));
            Organization oldParent = existing.get(normalize(row.parentCode()));
            if (newParent != null) {
                if (!"UNIT".equals(newParent.orgType())) {
                    issue(errors, prefix + ".parentCode", "INVALID_REFERENCE", "Tổ chức cha phải là UNIT.");
                }
            } else if (oldParent == null || !activeReferences.contains(oldParent.getId())) {
                issue(errors, prefix + ".parentCode", "INVALID_REFERENCE",
                    "Không tìm thấy tổ chức cha đang hoạt động trong phạm vi quản lý.");
            } else if (!"UNIT".equals(oldParent.getOrgType())) {
                issue(errors, prefix + ".parentCode", "INVALID_REFERENCE", "Tổ chức cha phải là UNIT.");
            }
        }

        validateTeams(parsed.teams(), incoming, existing, activeReferences, errors);
        List<OrgRow> ordered = orderOrganizations(incoming, errors);
        if (!errors.isEmpty()) {
            throw new BusinessException(OrganizationResponseCode.IMPORT_INVALID, errors);
        }
        return new Plan(ordered, new HashMap<>(existing));
    }

    private void validateTeams(List<TeamRow> rows, Map<String, OrgRow> incoming, Map<String, Organization> existing,
                               Set<Long> activeReferences, List<FieldViolation> errors) {
        Set<String> codes = rows.stream().map(TeamRow::code).filter(code -> !code.isBlank() && code.length() <= 50)
            .map(this::normalize).collect(Collectors.toSet());
        List<Long> orgIds = existing.values().stream().map(Organization::getId).toList();
        Map<Long, Set<String>> existingCodes = codes.isEmpty() || orgIds.isEmpty() ? Map.of()
            : teams.findExistingCodes(orgIds, codes).stream().collect(Collectors.groupingBy(
                TeamRepository.ExistingCode::getOrgId,
                Collectors.mapping(TeamRepository.ExistingCode::getCode, Collectors.toSet())));
        Map<TeamKey, TeamRow> seen = new HashMap<>();
        for (TeamRow row : rows) {
            String prefix = "Teams[" + row.row() + "]";
            validateDto(new CreateTeamRequest(row.code(), row.name(), row.description()), prefix, errors);
            if (row.orgCode().isBlank()) {
                issue(errors, prefix + ".orgCode", "REQUIRED", "Mã tổ chức là bắt buộc.");
            } else if (row.orgCode().length() > 50) {
                issue(errors, prefix + ".orgCode", "INVALID_LENGTH", "Mã tổ chức tối đa 50 ký tự.");
            }
            if (row.code().isBlank()) {
                issue(errors, prefix + ".code", "REQUIRED", "Mã nhóm là bắt buộc khi nhập từ tệp.");
            }
            String orgCode = normalize(row.orgCode());
            Organization org = existing.get(orgCode);
            boolean existingAllowed = org != null && activeReferences.contains(org.getId());
            if (!incoming.containsKey(orgCode) && !existingAllowed) {
                issue(errors, prefix + ".orgCode", "INVALID_REFERENCE",
                    "Không tìm thấy tổ chức đang hoạt động trong phạm vi quản lý.");
            }
            if (existingAllowed && existingCodes.getOrDefault(org.getId(), Set.of()).contains(normalize(row.code()))) {
                issue(errors, prefix + ".code", "ALREADY_EXISTS", "Mã nhóm đã được sử dụng trong tổ chức này.");
            }
            TeamRow previous = seen.putIfAbsent(new TeamKey(orgCode, normalize(row.code())), row);
            if (previous != null) {
                issue(errors, prefix + ".code", "DUPLICATE_IN_FILE", "Mã nhóm bị lặp trong cùng tổ chức của tệp.");
                issue(errors, "Teams[" + previous.row() + "].code", "DUPLICATE_IN_FILE",
                    "Mã nhóm bị lặp trong cùng tổ chức của tệp.");
            }
        }
    }

    /** Kahn sắp thứ tự cha trước con, không đệ quy Java hoặc phụ thuộc thứ tự dòng trong Excel. */
    private List<OrgRow> orderOrganizations(Map<String, OrgRow> incoming, List<FieldViolation> errors) {
        Map<String, List<OrgRow>> children = new HashMap<>();
        var queue = new ArrayDeque<OrgRow>();
        for (OrgRow row : incoming.values()) {
            String parent = normalize(row.parentCode());
            if (incoming.containsKey(parent)) {
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(row);
            } else {
                queue.add(row);
            }
        }
        List<OrgRow> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            OrgRow row = queue.remove();
            ordered.add(row);
            visited.add(normalize(row.code()));
            queue.addAll(children.getOrDefault(normalize(row.code()), List.of()));
        }
        for (OrgRow row : incoming.values()) {
            if (!visited.contains(normalize(row.code()))) {
                issue(errors, "Organizations[" + row.row() + "].parentCode", "CYCLE_DETECTED",
                    "Không xác định được thứ tự cha-con; vui lòng kiểm tra chu trình tổ chức.");
            }
        }
        return ordered;
    }

    private void persist(Parsed parsed, Plan plan, String actorId) {
        for (OrgRow row : plan.orderedOrganizations()) {
            Organization org = new Organization();
            org.setOrgType(row.orgType());
            org.setCode(row.code());
            org.setName(row.name());
            org.setSortNo(row.sortNo());
            if (!row.parentCode().isBlank()) {
                org.setParentId(plan.references().get(normalize(row.parentCode())).getId());
            }
            organizations.save(org);
            plan.references().put(normalize(row.code()), org);
            audit.record(actorId, "ORG_CREATED", "ORG", org.getId().toString(), org.getId(),
                Map.of("code", org.getCode(), "orgType", org.getOrgType(), "source", "IMPORT"));
        }
        for (TeamRow row : parsed.teams()) {
            Team team = new Team();
            team.setOrgId(plan.references().get(normalize(row.orgCode())).getId());
            team.setCode(row.code());
            team.setName(row.name());
            team.setDescription(row.description().isBlank() ? null : row.description());
            teams.save(team);
            audit.record(actorId, "TEAM_CREATED", "TEAM", team.getId().toString(), team.getOrgId(),
                Map.of("name", team.getName(), "source", "IMPORT"));
        }
        organizations.flush();
        teams.flush();
    }

    private <T> void validateDto(T dto, String prefix, List<FieldViolation> errors) {
        var violations = validator.validate(dto).stream().sorted(Comparator.comparingInt(violation ->
            violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName().startsWith("Not")
                ? 0 : 1)).toList();
        for (var violation : violations) {
            var error = ValidationErrors.fromConstraint(prefix + "." + violation.getPropertyPath(),
                violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName());
            issue(errors, error.field(), error.code(), error.message());
        }
    }

    private void issue(List<FieldViolation> errors, String field, String code, String message) {
        if (errors.size() < OrganizationImportWorkbook.MAX_ERRORS
            && errors.stream().noneMatch(error -> error.field().equals(field))) {
            errors.add(new FieldViolation(field, code, message));
        }
    }

    private String normalize(String code) {
        return code.toLowerCase(Locale.ROOT);
    }
}
