package vn.com.coreacademy.community.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.SearchDtos.SearchPageView;
import vn.com.coreacademy.community.service.CommunitySearchService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community search")
public class CommunitySearchController {
    private final CommunitySearchService search;

    @GetMapping("/search")
    @Operation(summary = "Tìm kiếm thống nhất kho tri thức", description = """
        Tìm trong Community, Learning và Knowledge theo quyền của từng nguồn.
        - Bộ lọc riêng Community loại các nguồn không hỗ trợ; xem appliedSourceTypes trong kết quả.
        """)
    @ApiResponse(responseCode = "200", description = "Page/count hợp nhất tại DB sau ACL.", useReturnTypeSchema = true)
    public BaseResponse<SearchPageView> search(@RequestParam String q,
        @RequestParam(required = false) List<String> sourceTypes, @RequestParam(required = false) String kind,
        @RequestParam(required = false) List<Long> tagIds, @RequestParam(required = false) Long guildId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "rank") String sort) {
        return BaseResponse.ok(search.search(q, sourceTypes, kind, tagIds, guildId, page, size, sort));
    }
}
