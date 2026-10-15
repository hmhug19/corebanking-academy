package vn.com.coreacademy.identity.dto;
import java.util.List;
import java.util.Map;
public record UserRights(List<RoleGrant> roles, List<FunctionInfo> functions, Map<String, List<Scope>> functionScopes) {}
