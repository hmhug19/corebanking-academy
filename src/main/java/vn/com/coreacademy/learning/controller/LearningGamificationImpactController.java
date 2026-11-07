package vn.com.coreacademy.learning.controller;

import java.time.LocalDate;

import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.GamificationImpactDtos.ImpactReport;
import vn.com.coreacademy.learning.service.GamificationImpactService;

@RestController
@RequestMapping("/api/v1/reports/learning/gamification-impact")
@Tag(name = "LearningGamificationImpactController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningGamificationImpactController {
    private final GamificationImpactService impact;

    @GetMapping
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Báo cáo tác động của gamification",
        description = """
            Chỉ số tổng hợp cho học viên trong phạm vi báo cáo của người gọi (quản trị hệ thống, quản trị đơn vị, giảng
            viên, người xem báo cáo); vai trò khác nhận 403. Không có dòng theo người.
            - from/to là ngày (giờ Việt Nam), cả hai bao gồm; mặc định 28 ngày kết thúc hôm nay, tối đa 366 ngày.
            - orgId thu hẹp về một đơn vị và cây con; ngoài phạm vi của người gọi thì kết quả rỗng.
            - Học viên hoạt động theo tuần: có dòng sổ XP (không tính điều chỉnh) trong tuần.
            - Khóa được giao: lần giao chưa thu hồi có hạn bắt đầu trong kỳ; hoàn thành đúng hạn khi xong trước hạn hoặc
              không có hạn.
            - So sánh trước/sau: hai cửa sổ liền kề 28 ngày quanh mốc ra mắt (lúc lưu phiên bản cấu hình 1), không phụ
              thuộc kỳ đã chọn; after là null nếu chưa qua ngày nào.
            - Tỷ lệ là phần trăm một chữ số thập phân, null khi không có mẫu.
            """
    )
    public BaseResponse<ImpactReport> report(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) @Positive Long orgId) {
        return BaseResponse.ok(impact.report(from, to, orgId));
    }

    @GetMapping(value = "/export", produces = {"text/csv", MediaType.APPLICATION_JSON_VALUE})
    @ApiResponse(responseCode = "200", useReturnTypeSchema = true)
    @Operation(
        summary = "Xuất báo cáo tác động ra CSV",
        description = """
            Cùng bộ lọc và phạm vi với báo cáo; mỗi dòng (Nhóm, Chỉ số, Giá trị) là một số trên màn hình, dựng từ chính
            báo cáo nên khớp. UTF-8 BOM cho Excel, xử lý ký tự công thức. Không có dòng theo người.
            """
    )
    public ResponseEntity<byte[]> export(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false) @Positive Long orgId) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=gamification-impact.csv")
            .body(impact.export(from, to, orgId));
    }
}
