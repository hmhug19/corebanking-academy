package vn.com.coreacademy.identity.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

public record UserRights(List<RoleGrant> roles, Set<String> permissions, Map<String, List<Scope>> permissionScopes) {
}
