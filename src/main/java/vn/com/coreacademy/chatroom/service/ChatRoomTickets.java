package vn.com.coreacademy.chatroom.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.chatroom.config.ChatRoomProperties;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.exception.ResponseCode;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.security.SecurityContextUtils;

/** Vé ngắn hạn dùng một lần; JWT không xuất hiện trong URL, packet hoặc log WebSocket. */
@Service
@RequiredArgsConstructor
public class ChatRoomTickets {
    public static final String ACCESS_ATTRIBUTE = "chatRoomAccess";
    private final ChatRoomProperties properties;
    private final LearningIdentityService identity;
    private final Map<String, Entry> pending = new HashMap<>();

    public record Access(String userId, long version, Instant tokenExpiresAt) {
    }

    public record Ticket(String ticket, Instant expiresAt) {
        @Override
        public String toString() {
            return "Ticket[redacted]";
        }
    }

    private record Entry(Access access, Instant expiresAt) {
    }

    public synchronized Ticket issue() {
        String userId = SecurityContextUtils.getUserId();
        long version = SecurityContextUtils.getAuthorizationVersion();
        identity.requireChatRoomSession(userId, version);
        var authentication = (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
        Instant tokenExpiresAt = authentication.getToken().getExpiresAt();
        if (tokenExpiresAt == null || !tokenExpiresAt.isAfter(Instant.now())) {
            throw new BusinessException(ResponseCode.UNAUTHORIZED);
        }

        pending.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(Instant.now()));
        long ownTickets = pending.values().stream().filter(e -> e.access().userId().equals(userId)).count();
        if (pending.size() >= 256 || ownTickets >= 4) {
            throw new BusinessException(ResponseCode.TOO_MANY_REQUESTS);
        }
        String ticket = UUID.randomUUID().toString() + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(properties.ticketTtl());
        pending.put(ticket, new Entry(new Access(userId, version, tokenExpiresAt), expiresAt));
        return new Ticket(ticket, expiresAt);
    }

    public synchronized Access consume(String ticket) {
        Entry entry = pending.remove(ticket);
        if (entry == null || !entry.expiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ResponseCode.UNAUTHORIZED);
        }
        validate(entry.access());
        return entry.access();
    }

    public void validate(Access access) {
        if (!access.tokenExpiresAt().isAfter(Instant.now())) {
            throw new BusinessException(ResponseCode.UNAUTHORIZED);
        }
        identity.requireChatRoomSession(access.userId(), access.version());
    }
}
