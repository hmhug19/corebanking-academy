package vn.com.coreacademy.identity.dto;

/** The one answer to a registration request, the same whether or not the staff code already exists. */
public record RegistrationResult(String message) {
}
