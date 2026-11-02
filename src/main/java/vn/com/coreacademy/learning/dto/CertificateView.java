package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A certificate: one completed enrollment cycle of the caller, and id is the enrollment id. courseTitle is the title
 * of the revision that was learned, and can be null as on the course card. bestScorePercent is the card's value, the
 * best score of the course's final exam, null when the course has none or it was never graded. verificationCode is
 * CBA-{courseCode}-{userId}-{cycleNo} in upper case.
 */
public record CertificateView(Long id, Long courseId, String courseCode, String courseTitle, Integer cycleNo,
                              Long revisionId, String userId, String fullName, Instant completedAt,
                              BigDecimal bestScorePercent, String verificationCode) {
}
