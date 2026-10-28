package vn.com.coreacademy.community.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
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
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.GuildDtos.CreateGuild;
import vn.com.coreacademy.community.dto.GuildDtos.GuildView;
import vn.com.coreacademy.community.dto.GuildDtos.InvitationResponse;
import vn.com.coreacademy.community.dto.GuildDtos.InviteMembers;
import vn.com.coreacademy.community.dto.GuildDtos.MemberAction;
import vn.com.coreacademy.community.dto.GuildDtos.MembershipView;
import vn.com.coreacademy.community.dto.GuildDtos.TransferOwner;
import vn.com.coreacademy.community.dto.GuildDtos.UpdateGuild;
import vn.com.coreacademy.community.dto.GuildDtos.VersionRequest;
import vn.com.coreacademy.community.repository.ContentRepository;
import vn.com.coreacademy.community.service.CommunityContentService;
import vn.com.coreacademy.community.service.CommunityGuildService;

@RestController
@RequestMapping(value = "/api/v1/community/guilds", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Community guilds")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CommunityGuildController {
    private final CommunityGuildService guilds;
    private final CommunityContentService contents;

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Danh sách Guild có quyền xem.", useReturnTypeSchema = true)
    @Operation(summary = "Danh sách Guild", description = """
        Lọc Guild private trước count và phân trang; không tự tham gia Guild.
        """)
    public BaseResponse<PageResponse<GuildView>> list(@RequestParam(required = false) String q,
        @RequestParam(required = false) String visibility, @RequestParam(defaultValue = "false") boolean mine,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(guilds.list(q, visibility, mine, page, size));
    }

    @PostMapping
    @ApiResponse(responseCode = "200", description = "Guild và membership owner.", useReturnTypeSchema = true)
    @Operation(summary = "Tạo Guild", description = """
        Tạo Guild và owner cùng transaction; private chỉ nhận lời mời.
        """)
    public BaseResponse<GuildView> create(@Valid @RequestBody CreateGuild request) {
        return BaseResponse.ok(guilds.create(request));
    }

    @GetMapping("/{guildId}")
    @ApiResponse(responseCode = "200", description = "Metadata và khả năng thao tác Guild.", useReturnTypeSchema = true)
    @Operation(summary = "Chi tiết Guild", description = """
        Invitee chỉ có metadata cần phản hồi; membership chưa active không mở nội dung Guild.
        """)
    public BaseResponse<GuildView> detail(@PathVariable @Positive Long guildId) {
        return BaseResponse.ok(guilds.detail(guildId));
    }

    @PutMapping("/{guildId}")
    @ApiResponse(responseCode = "200", description = "Metadata Guild mới.", useReturnTypeSchema = true)
    @Operation(summary = "Cập nhật Guild", description = """
        Owner hoặc org moderator cập nhật metadata với version hiện tại.
        """)
    public BaseResponse<GuildView> update(@PathVariable @Positive Long guildId,
        @Valid @RequestBody UpdateGuild request) {
        return BaseResponse.ok(guilds.update(guildId, request));
    }

    @PostMapping("/{guildId}/join")
    @ApiResponse(responseCode = "200", description = "Membership active hoặc pending.", useReturnTypeSchema = true)
    @Operation(summary = "Tham gia Guild", description = """
        Open kích hoạt ngay, approval chờ duyệt; invite-only không cho tự tham gia.
        """)
    public BaseResponse<MembershipView> join(@PathVariable @Positive Long guildId) {
        return BaseResponse.ok(guilds.join(guildId));
    }

    @PostMapping("/{guildId}/invitations")
    @ApiResponse(responseCode = "200", description = "Trạng thái lời mời của từng người.", useReturnTypeSchema = true)
    @Operation(summary = "Mời thành viên Guild", description = """
        Owner hoặc moderator mời người cùng tổ chức; retry không ghi đè membership hiện có.
        """)
    public BaseResponse<List<MembershipView>> invite(@PathVariable @Positive Long guildId,
        @Valid @RequestBody InviteMembers request) {
        return BaseResponse.ok(guilds.invite(guildId, request));
    }

    @PutMapping("/{guildId}/members/{userId}")
    @ApiResponse(responseCode = "200", description = "Membership sau xử lý.", useReturnTypeSchema = true)
    @Operation(summary = "Quản lý thành viên Guild", description = """
        Duyệt, từ chối, loại thành viên hoặc đổi role; chỉ owner quản lý moderator.
        """)
    public BaseResponse<MembershipView> member(@PathVariable @Positive Long guildId,
        @PathVariable @Size(min = 1, max = 20) String userId, @Valid @RequestBody MemberAction request) {
        return BaseResponse.ok(guilds.memberAction(guildId, userId, request));
    }

    @PostMapping("/{guildId}/invitation-response")
    @ApiResponse(responseCode = "200", description = "Membership của người phản hồi.", useReturnTypeSchema = true)
    @Operation(summary = "Phản hồi lời mời Guild", description = """
        Chỉ người nhận phản hồi lời mời chưa hết hạn.
        """)
    public BaseResponse<MembershipView> invitation(@PathVariable @Positive Long guildId,
        @Valid @RequestBody InvitationResponse request) {
        return BaseResponse.ok(guilds.invitation(guildId, request));
    }

    @PostMapping("/{guildId}/leave")
    @ApiResponse(responseCode = "200", description = "Membership left.", useReturnTypeSchema = true)
    @Operation(summary = "Rời Guild", description = """
        Owner phải chuyển quyền sở hữu trước khi rời Guild.
        """)
    public BaseResponse<MembershipView> leave(@PathVariable @Positive Long guildId,
        @Valid @RequestBody VersionRequest request) {
        return BaseResponse.ok(guilds.leave(guildId, request.expectedVersion()));
    }

    @PostMapping("/{guildId}/transfer-owner")
    @ApiResponse(responseCode = "200", description = "Guild và owner mới.", useReturnTypeSchema = true)
    @Operation(summary = "Chuyển owner Guild", description = """
        Chuyển nguyên tử cho active member cùng tổ chức; org moderator điều phối khi owner mất hiệu lực.
        """)
    public BaseResponse<GuildView> transfer(@PathVariable @Positive Long guildId,
        @Valid @RequestBody TransferOwner request) {
        return BaseResponse.ok(guilds.transfer(guildId, request));
    }

    @GetMapping("/{guildId}/members")
    @ApiResponse(responseCode = "200", description = "Danh sách membership trong quyền.", useReturnTypeSchema = true)
    @Operation(summary = "Danh sách thành viên Guild", description = """
        Thành viên thường chỉ thấy active; owner/moderator có thể lọc các trạng thái quản lý.
        """)
    public BaseResponse<PageResponse<MembershipView>> members(@PathVariable @Positive Long guildId,
        @RequestParam(required = false) String q, @RequestParam(required = false) String status,
        @RequestParam(required = false) String role, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(guilds.members(guildId, q, status, role, page, size));
    }

    @GetMapping("/{guildId}/feed")
    @ApiResponse(responseCode = "200", description = "Feed lọc theo cả Guild và content ACL.",
        useReturnTypeSchema = true)
    @Operation(summary = "Feed của Guild", description = """
        Có quyền xem Guild không đồng nghĩa có quyền xem mọi nội dung được gắn vào feed.
        """)
    public BaseResponse<PageResponse<ContentCard>> feed(@PathVariable @Positive Long guildId,
        @RequestParam(required = false) String kind, @RequestParam(required = false) List<Long> tagIds,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        guilds.requireFeed(guildId);
        return BaseResponse.ok(contents.list(new ContentRepository.Search(kind, null, tagIds, null, null,
            false, null, null, page, size, null, guildId)));
    }
}
