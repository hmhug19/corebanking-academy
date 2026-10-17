package vn.com.coreacademy.identity.dto;

import java.util.List;

public record UserDetail(UserInfo user, List<ManagedRoleGrant> roleGrants, boolean editable) {
}
