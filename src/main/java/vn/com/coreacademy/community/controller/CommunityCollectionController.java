package vn.com.coreacademy.community.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import vn.com.coreacademy.community.dto.CollectionDtos.BookmarkRequest;
import vn.com.coreacademy.community.dto.CollectionDtos.BookmarkView;
import vn.com.coreacademy.community.dto.CollectionDtos.CollectionItems;
import vn.com.coreacademy.community.dto.CollectionDtos.CollectionView;
import vn.com.coreacademy.community.dto.CollectionDtos.CreateCollection;
import vn.com.coreacademy.community.dto.CollectionDtos.ItemResult;
import vn.com.coreacademy.community.dto.CollectionDtos.SaveItem;
import vn.com.coreacademy.community.dto.CollectionDtos.UpdateCollection;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.service.CommunityCollectionService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Community collections")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CommunityCollectionController {
    private final CommunityCollectionService collections;

    @GetMapping("/me/bookmarks")
    @ApiResponse(responseCode = "200", description = "Dấu trang còn quyền đọc.", useReturnTypeSchema = true)
    @Operation(summary = "Danh sách đã lưu")
    public BaseResponse<PageResponse<ContentCard>> bookmarks(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(collections.bookmarks(page, size));
    }

    @PutMapping("/me/bookmarks/{contentId}")
    @ApiResponse(responseCode = "200", description = "SET dấu trang, retry không nhân bản.", useReturnTypeSchema = true)
    @Operation(summary = "Lưu hoặc bỏ lưu nội dung")
    public BaseResponse<BookmarkView> bookmark(@PathVariable @Positive Long contentId,
        @Valid @RequestBody BookmarkRequest request) {
        return BaseResponse.ok(collections.bookmark(contentId, request.saved()));
    }

    @GetMapping("/collections")
    @ApiResponse(responseCode = "200", description = "Bộ sưu tập có ACL, count/page tại DB.",
        useReturnTypeSchema = true)
    @Operation(summary = "Danh sách bộ sưu tập")
    public BaseResponse<PageResponse<CollectionView>> list(@RequestParam(required = false) String q,
        @RequestParam(defaultValue = "false") boolean mine, @RequestParam(required = false) String visibility,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(collections.list(q, mine, visibility, page, size));
    }

    @PostMapping("/collections")
    @ApiResponse(responseCode = "200", description = "Bộ sưu tập đã tạo.", useReturnTypeSchema = true)
    @Operation(summary = "Tạo bộ sưu tập")
    public BaseResponse<CollectionView> create(@Valid @RequestBody CreateCollection request) {
        return BaseResponse.ok(collections.create(request));
    }

    @GetMapping("/collections/{collectionId}/items")
    @ApiResponse(responseCode = "200", description = "Collection và item còn quyền nguồn.", useReturnTypeSchema = true)
    @Operation(summary = "Nội dung bộ sưu tập")
    public BaseResponse<CollectionItems> items(@PathVariable @Positive Long collectionId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(collections.items(collectionId, page, size));
    }

    @PutMapping("/collections/{collectionId}")
    @ApiResponse(responseCode = "200", description = "Collection và version mới.", useReturnTypeSchema = true)
    @Operation(summary = "Đổi metadata hoặc ACL bộ sưu tập")
    public BaseResponse<CollectionView> update(@PathVariable @Positive Long collectionId,
        @Valid @RequestBody UpdateCollection request) {
        return BaseResponse.ok(collections.update(collectionId, request));
    }

    @PutMapping("/collections/{collectionId}/items/{contentId}")
    @ApiResponse(responseCode = "200", description = "Item/null và collectionVersion.", useReturnTypeSchema = true)
    @Operation(summary = "Thêm, sửa hoặc bỏ item bộ sưu tập")
    public BaseResponse<ItemResult> item(@PathVariable @Positive Long collectionId,
        @PathVariable @Positive Long contentId, @Valid @RequestBody SaveItem request) {
        return BaseResponse.ok(collections.item(collectionId, contentId, request));
    }

    @DeleteMapping("/collections/{collectionId}")
    @ApiResponse(responseCode = "200", description = "Lưu trữ collection, không xóa bài nguồn.",
        useReturnTypeSchema = true)
    @Operation(summary = "Lưu trữ bộ sưu tập")
    public BaseResponse<Void> archive(@PathVariable @Positive Long collectionId,
        @RequestParam @Min(0) Long expectedVersion) {
        collections.archive(collectionId, expectedVersion);
        return BaseResponse.ok();
    }
}
