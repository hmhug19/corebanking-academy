package vn.com.coreacademy.community.controller;

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
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.JobDtos.JobView;
import vn.com.coreacademy.community.dto.JobDtos.Reconcile;
import vn.com.coreacademy.community.dto.JobDtos.Reindex;
import vn.com.coreacademy.community.service.CommunityJobService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community jobs")
public class CommunityJobController {
    private final CommunityJobService jobs;

    @PostMapping("/moderation/search/reindex")
    @Operation(summary = "Yêu cầu lập lại chỉ mục tri thức")
    @ApiResponse(responseCode = "200", description = "Job dedup theo actor/requestId, không trả payload.",
        useReturnTypeSchema = true)
    public BaseResponse<JobView> reindex(@Valid @RequestBody Reindex request) {
        return BaseResponse.ok(jobs.reindex(request));
    }

    @PostMapping("/moderation/contributions/reconcile")
    @Operation(summary = "Đối soát đóng góp cộng đồng")
    @ApiResponse(responseCode = "200", description = "Job trong phạm vi org, không sửa điểm bằng request.",
        useReturnTypeSchema = true)
    public BaseResponse<JobView> reconcile(@Valid @RequestBody Reconcile request) {
        return BaseResponse.ok(jobs.reconcile(request));
    }

    @GetMapping("/jobs/{eventId}")
    @Operation(summary = "Xem tiến độ công việc cộng đồng")
    @ApiResponse(responseCode = "200", description = "Chỉ creator hoặc moderator đúng org.", useReturnTypeSchema = true)
    public BaseResponse<JobView> get(@PathVariable @Positive Long eventId) {
        return BaseResponse.ok(jobs.get(eventId));
    }
}
