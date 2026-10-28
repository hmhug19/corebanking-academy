package vn.com.coreacademy.community.controller;

import java.time.Instant;
import java.util.List;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.DocumentRequests.CreateRecording;
import vn.com.coreacademy.community.dto.NotificationDtos.Retry;
import vn.com.coreacademy.community.dto.RecordingDtos.CreateTranscript;
import vn.com.coreacademy.community.dto.RecordingDtos.RecordingCard;
import vn.com.coreacademy.community.dto.RecordingDtos.TranscriptView;
import vn.com.coreacademy.community.dto.RecordingDtos.UpdateTranscript;
import vn.com.coreacademy.community.service.CommunityRecordingService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community recordings")
public class CommunityRecordingController {
    private final CommunityRecordingService recordings;

    @GetMapping("/recordings")
    @Operation(summary = "Tìm video chia sẻ kỹ thuật")
    @ApiResponse(responseCode = "200", description = "Metadata từ revision đã xuất bản còn ACL.",
        useReturnTypeSchema = true)
    public BaseResponse<PageResponse<RecordingCard>> list(@RequestParam(required = false) String q,
        @RequestParam(required = false) List<Long> tagIds, @RequestParam(required = false) Long guildId,
        @RequestParam(required = false) String speaker, @RequestParam(required = false) Instant heldFrom,
        @RequestParam(required = false) Instant heldTo, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(recordings.list(q, tagIds, guildId, speaker, heldFrom, heldTo, page, size));
    }

    @PostMapping("/recordings")
    @Operation(summary = "Tạo bài chia sẻ video")
    @ApiResponse(responseCode = "200", description = "Bản nháp nội dung video.", useReturnTypeSchema = true)
    public BaseResponse<ContentDetail> create(@Valid @RequestBody CreateRecording request) {
        return BaseResponse.ok(recordings.create(request));
    }

    @PostMapping("/assets/{assetId}/transcripts")
    @Operation(summary = "Soạn bản chép lời video")
    @ApiResponse(responseCode = "200", description = "Draft transcript có segment đã kiểm duration.",
        useReturnTypeSchema = true)
    public BaseResponse<TranscriptView> createTranscript(@PathVariable @Positive Long assetId,
        @Valid @RequestBody CreateTranscript request) {
        return BaseResponse.ok(recordings.createTranscript(assetId, request));
    }

    @PutMapping("/transcripts/{transcriptId}")
    @Operation(summary = "Sửa bản chép lời nháp")
    @ApiResponse(responseCode = "200", description = "Draft sau kiểm version và toàn bộ nguồn dùng chung.",
        useReturnTypeSchema = true)
    public BaseResponse<TranscriptView> update(@PathVariable @Positive Long transcriptId,
        @Valid @RequestBody UpdateTranscript request) {
        return BaseResponse.ok(recordings.updateTranscript(transcriptId, request));
    }

    @PostMapping("/transcripts/{transcriptId}/publish")
    @Operation(summary = "Xuất bản chép lời video")
    @ApiResponse(responseCode = "200", description = "Transcript published bất biến; bản cũ superseded.",
        useReturnTypeSchema = true)
    public BaseResponse<TranscriptView> publish(@PathVariable @Positive Long transcriptId,
        @Valid @RequestBody Retry request) {
        return BaseResponse.ok(recordings.publishTranscript(transcriptId, request));
    }

    @GetMapping("/revision-assets/{revisionAssetId}/transcript")
    @Operation(summary = "Đọc bản chép lời đã xuất bản")
    @ApiResponse(responseCode = "200", description = "Chỉ trả transcript khi caller còn quyền nguồn.",
        useReturnTypeSchema = true)
    public BaseResponse<TranscriptView> get(@PathVariable @Positive Long revisionAssetId) {
        return BaseResponse.ok(recordings.transcript(revisionAssetId));
    }
}
