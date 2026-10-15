package vn.com.coreacademy.identity.dto;

import java.util.List;
import java.util.Map;

public record MeResponse(UserInfo user, List<String> roles, List<String> functions,
                         List<RoleGrant> roleGrants, List<FunctionInfo> functionDetails,
                         Map<String, List<Scope>> functionScopes) {}
