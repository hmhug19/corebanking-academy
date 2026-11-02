package vn.com.coreacademy.learning.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Chứng nhận: một chu kỳ ghi danh đã hoàn thành của người gọi, id chính là id ghi danh. courseTitle là tiêu đề của
 * phiên bản khóa học đã học, có thể null như trên thẻ khóa học. bestScorePercent là giá trị của thẻ khóa học, tức điểm
 * cao nhất của bài thi cuối khóa, null khi khóa không có bài thi cuối hoặc chưa có lượt chấm. verificationCode có dạng
 * CBA-{courseCode}-{userId}-{cycleNo}, viết hoa.
 */
public record CertificateView(Long id, Long courseId, String courseCode, String courseTitle, Integer cycleNo,
                              Long revisionId, String userId, String fullName, Instant completedAt,
                              BigDecimal bestScorePercent, String verificationCode) {
}
