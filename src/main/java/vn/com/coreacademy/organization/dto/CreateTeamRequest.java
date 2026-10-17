package vn.com.coreacademy.organization.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTeamRequest(@Size(max = 50) String code, @NotBlank @Size(max = 255) String name,
                                @Size(max = 4000) String description) {
}
