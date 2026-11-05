package vn.com.coreacademy.identity.dto;

/** A unit a visitor can register into: id and name only. */
public record RegistrationUnit(Long orgId, String name) {
}
