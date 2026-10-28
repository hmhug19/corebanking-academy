package vn.com.coreacademy.community.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.community.dto.ContentRequests.GrantInput;
import vn.com.coreacademy.community.dto.ContentViews.GrantView;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;

public final class CollectionDtos {
    public record CreateCollection(
        @NotNull UUID clientRequestId,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 10_000) String description,
        @NotNull @Pattern(regexp = "private|organization|guild|restricted") String visibility,
        @Positive Long primaryGuildId,
        @NotNull @Valid @Size(max = 100) List<GrantInput> grants) {
    }

    public record UpdateCollection(
        @NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 10_000) String description,
        @NotNull @Pattern(regexp = "private|organization|guild|restricted") String visibility,
        @Positive Long primaryGuildId,
        @NotNull @Valid @Size(max = 100) List<GrantInput> grants,
        @NotNull @Pattern(regexp = "active|archived") String status) {
    }

    public record SaveItem(@NotNull @Min(0) Long expectedCollectionVersion, boolean saved,
                           @Size(max = 10_000) String note, @Min(0) int sortNo) {
    }

    public record BookmarkRequest(boolean saved) {
    }

    public record BookmarkView(Long contentId, boolean saved) {
    }

    public record CollectionCapabilities(boolean canEditMetadata, boolean canEditItems, boolean canArchive) {
    }

    public record CollectionView(Long collectionId, PersonRef owner, String name, String description,
                                 String visibility, Long primaryGuildId, String status, long visibleItemCount,
                                 boolean isDefault, CollectionCapabilities capabilities, List<GrantView> grants,
                                 Long version, Instant createTime, Instant updateTime) {
    }

    public record ContentRef(Long contentId, String kind, Long revisionId, String title, String url) {
    }

    public record CollectionItemView(Long itemId, Long collectionId, ContentRef content, String note,
                                     Integer sortNo, Long collectionVersion) {
    }

    public record CollectionItems(CollectionView collection, List<CollectionItemView> items, int page, int size,
                                  long totalElements, int totalPages) {
        public CollectionItems(CollectionView collection, PageResponse<CollectionItemView> page) {
            this(collection, page.items(), page.page(), page.size(), page.totalElements(), page.totalPages());
        }
    }

    public record ItemResult(CollectionItemView item, Long collectionVersion) {
    }

    private CollectionDtos() {
    }
}
