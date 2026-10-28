package vn.com.coreacademy.community.controller;

import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.ContributionDtos.BadgeAwardView;
import vn.com.coreacademy.community.dto.ContributionDtos.BadgeView;
import vn.com.coreacademy.community.dto.ContributionDtos.Contributions;
import vn.com.coreacademy.community.dto.ContributionDtos.ContributorRank;
import vn.com.coreacademy.community.dto.ContributionDtos.CreateBadge;
import vn.com.coreacademy.community.dto.ContributionDtos.RetireBadge;
import vn.com.coreacademy.community.service.CommunityContributionService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community contributions")
public class CommunityContributionController {
    private final CommunityContributionService contributions;

    @GetMapping("/me/contributions")
    @Operation(summary = "Xem lịch sử đóng góp của tôi")
    @ApiResponse(responseCode = "200", description = "Ledger còn ACL và điểm hợp lệ hiện tại.",
        useReturnTypeSchema = true)
    public BaseResponse<Contributions> personal(@RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(contributions.personal(from, to, page, size));
    }

    @GetMapping("/leaderboard")
    @Operation(summary = "Xem bảng đóng góp cộng đồng")
    @ApiResponse(responseCode = "200",
        description = "Chỉ nguồn organization còn hợp lệ; không chờ worker thu hồi quyền.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<ContributorRank>> leaderboard(@RequestParam(required = false) Instant from,
        @RequestParam(required = false) Instant to, @RequestParam(required = false) Long guildId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(contributions.leaderboard(from, to, guildId, page, size));
    }

    @GetMapping("/badges")
    @Operation(summary = "Xem danh mục huy hiệu")
    @ApiResponse(responseCode = "200", description = "Các phiên bản luật huy hiệu của org.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<BadgeView>> badges(@RequestParam(required = false) String q,
        @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(contributions.definitions(q, status, page, size));
    }

    @PostMapping("/badges")
    @Operation(summary = "Định nghĩa luật huy hiệu")
    @ApiResponse(responseCode = "200", description = "Luật allowlist, không thực thi expression.",
        useReturnTypeSchema = true)
    public BaseResponse<BadgeView> create(@Valid @RequestBody CreateBadge request) {
        return BaseResponse.ok(contributions.create(request));
    }

    @PostMapping("/badges/{badgeId}/retire")
    @Operation(summary = "Ngừng trao huy hiệu theo luật cũ")
    @ApiResponse(responseCode = "200", description = "Retired giữ lịch sử award.", useReturnTypeSchema = true)
    public BaseResponse<BadgeView> retire(@PathVariable @Positive Long badgeId,
        @Valid @RequestBody RetireBadge request) {
        return BaseResponse.ok(contributions.retire(badgeId, request));
    }

    @GetMapping("/users/{userId}/badges")
    @Operation(summary = "Xem huy hiệu còn đủ điều kiện")
    @ApiResponse(responseCode = "200", description = "Chỉ nguồn công khai hiện tại cùng org.",
        useReturnTypeSchema = true)
    public BaseResponse<PageResponse<BadgeAwardView>> awards(@PathVariable String userId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(contributions.awards(userId, page, size));
    }
}
