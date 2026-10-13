package vn.com.coreacademy.common.dto;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String message) {
}
