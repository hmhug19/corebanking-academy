package vn.com.coreacademy.community.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.PreferenceDtos.ExpertView;
import vn.com.coreacademy.community.dto.PreferenceDtos.ExpertiseResult;
import vn.com.coreacademy.community.dto.PreferenceDtos.ExpertiseView;
import vn.com.coreacademy.community.dto.PreferenceDtos.Onboarding;
import vn.com.coreacademy.community.dto.PreferenceDtos.PreferencesView;
import vn.com.coreacademy.community.dto.PreferenceDtos.SaveExpertise;
import vn.com.coreacademy.community.dto.PreferenceDtos.SavePreferences;
import vn.com.coreacademy.community.dto.PreferenceDtos.SubscriptionRequest;
import vn.com.coreacademy.community.dto.PreferenceDtos.SubscriptionView;
import vn.com.coreacademy.community.service.CommunityPreferenceService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Community preferences and directory")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CommunityPreferenceController {
    private final CommunityPreferenceService preferences;

    @GetMapping("/me/preferences")
    @ApiResponse(responseCode = "200", description = "Tùy chọn của caller.", useReturnTypeSchema = true)
    @Operation(summary = "Đọc tùy chọn người dùng")
    public BaseResponse<PreferencesView> get() {
        return BaseResponse.ok(preferences.get());
    }

    @PutMapping("/me/preferences")
    @ApiResponse(responseCode = "200", description = "Tùy chọn và version mới.", useReturnTypeSchema = true)
    @Operation(summary = "Cập nhật lịch digest và thông báo")
    public BaseResponse<PreferencesView> save(@Valid @RequestBody SavePreferences request) {
        return BaseResponse.ok(preferences.save(request));
    }

    @PostMapping("/me/onboarding")
    @ApiResponse(responseCode = "200", description = "Tùy chọn sau onboarding.", useReturnTypeSchema = true)
    @Operation(summary = "Hoàn tất onboarding và theo dõi chủ đề")
    public BaseResponse<PreferencesView> onboarding(@Valid @RequestBody Onboarding request) {
        return BaseResponse.ok(preferences.onboarding(request));
    }

    @GetMapping("/me/subscriptions")
    @ApiResponse(responseCode = "200", description = "Theo dõi còn quyền, phân trang tại DB.",
        useReturnTypeSchema = true)
    @Operation(summary = "Danh sách đang theo dõi")
    public BaseResponse<PageResponse<SubscriptionView>> subscriptions(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(preferences.subscriptions(page, size));
    }

    @PutMapping("/me/subscriptions")
    @ApiResponse(responseCode = "200", description = "Theo dõi đã SET hoặc null khi off.", useReturnTypeSchema = true)
    @Operation(summary = "Thiết lập theo dõi")
    public BaseResponse<SubscriptionView> subscribe(@Valid @RequestBody SubscriptionRequest request) {
        return BaseResponse.ok(preferences.subscribe(request));
    }

    @GetMapping("/experts")
    @ApiResponse(responseCode = "200", description = "Danh bạ kỹ năng cùng đơn vị.", useReturnTypeSchema = true)
    @Operation(summary = "Tìm người có chuyên môn")
    public BaseResponse<PageResponse<ExpertView>> experts(@RequestParam(required = false) String q,
        @RequestParam(required = false) List<Long> tagIds, @RequestParam(required = false) Long guildId,
        @RequestParam(required = false) Boolean available, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(preferences.experts(q, tagIds, guildId, available, page, size));
    }

    @GetMapping("/users/{userId}/expertise")
    @ApiResponse(responseCode = "200", description = "Kỹ năng tự khai, không có email/credential.",
        useReturnTypeSchema = true)
    @Operation(summary = "Xem kỹ năng người dùng")
    public BaseResponse<List<ExpertiseView>> expertise(@PathVariable @Size(min = 1, max = 20) String userId) {
        return BaseResponse.ok(preferences.expertise(userId));
    }

    @PutMapping("/me/expertise")
    @ApiResponse(responseCode = "200", description = "Kỹ năng và preferenceVersion mới.", useReturnTypeSchema = true)
    @Operation(summary = "Thay danh sách kỹ năng của tôi")
    public BaseResponse<ExpertiseResult> expertise(@Valid @RequestBody SaveExpertise request) {
        return BaseResponse.ok(preferences.expertise(request));
    }
}
