package vn.com.coreacademy.common.dto;

/** Never include rejected values: a field may contain a password or token. */
public record FieldViolation(String field, String code, String message) {}
