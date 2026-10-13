package vn.com.coreacademy.identity.dto;

import vn.com.coreacademy.identity.enums.ScopeType;

public record Scope(ScopeType type, Long orgId, Long teamId) {
}
