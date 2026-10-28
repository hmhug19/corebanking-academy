package vn.com.coreacademy.community.dto;

import java.time.Instant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;

public final class NotificationDtos {
    public record NotificationView(Long notificationId, String eventType, ContentRef content, PersonRef actor,
                                    Instant readAt, Instant deliveredAt, String deliveryMode, String message) {
    }

    public record DigestView(Long batchId, Instant windowStart, Instant windowEnd, String status,
                              Instant deliveredAt, long visibleItemCount, long unreadCount, Long version,
                              Instant createTime, Instant updateTime) {
    }

    public record DigestDetail(@JsonUnwrapped DigestView digest, PageResponse<NotificationView> items) {
    }

    public record Retry(@NotNull @Min(0) Long expectedVersion) {
    }

    private NotificationDtos() {
    }
}
