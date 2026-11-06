package vn.com.coreacademy.chatroom.service;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import vn.com.coreacademy.chatroom.config.ChatRoomProperties;
import vn.com.coreacademy.chatroom.constants.ChatRoomLimits;
import vn.com.coreacademy.common.exception.BusinessException;

/** Phòng chung trong bộ nhớ của một instance; mọi thay đổi và broadcast giữ cùng thứ tự. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRoomService {
    private static final Set<String> COLORS = Set.of("teal", "slate", "amber", "blue", "rose");
    private final ObjectMapper json;
    private final ChatRoomMap map;
    private final ChatRoomTickets tickets;
    private final ChatRoomProperties properties;
    private final Map<String, Guest> guests = new LinkedHashMap<>();
    private final Deque<Map<String, Object>> history = new ArrayDeque<>();

    private static class Guest {
        private final WebSocketSession session;
        private final ChatRoomTickets.Access access;
        private final long connectedAt = System.currentTimeMillis();
        private volatile String name;
        private String color;
        private double x;
        private double y;
        private double allowance = 30;
        private long lastMove = System.nanoTime();
        private long lastChat;
        private long packetWindow = System.currentTimeMillis();
        private volatile long lastPacket = System.currentTimeMillis();
        private int packets;

        private Guest(WebSocketSession session, ChatRoomTickets.Access access, int sendTimeoutMillis) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, sendTimeoutMillis,
                ChatRoomLimits.OUTBOUND_BUFFER_BYTES);
            this.access = access;
        }

        private Map<String, Object> view() {
            return Map.of("id", session.getId(), "name", name, "color", color, "x", x, "y", y);
        }
    }

    public synchronized void connect(WebSocketSession session) throws IOException {
        Object access = session.getAttributes().get(ChatRoomTickets.ACCESS_ATTRIBUTE);
        if (!(access instanceof ChatRoomTickets.Access identity)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (guests.size() >= ChatRoomLimits.MAX_PLAYERS) {
            session.close(new CloseStatus(1013, "Phòng đã đầy"));
            return;
        }
        session.setTextMessageSizeLimit(ChatRoomLimits.PACKET_BYTES);
        guests.put(session.getId(), new Guest(session, identity, Math.toIntExact(properties.sendTimeout().toMillis())));
    }

    public synchronized void receive(WebSocketSession session, String payload) throws IOException {
        Guest guest = guests.get(session.getId());
        if (guest == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!guest.access.tokenExpiresAt().isAfter(Instant.now())) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        guest.lastPacket = now;
        if (now - guest.packetWindow > 1000) {
            guest.packetWindow = now;
            guest.packets = 0;
        }
        if (++guest.packets > ChatRoomLimits.PACKETS_PER_SECOND) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        try {
            JsonNode packet = json.readTree(payload);
            if (packet == null || !packet.isObject()) {
                error(guest, "Dữ liệu không hợp lệ.");
                return;
            }
            String type = packet.path("type").asText();
            if ("ping".equals(type)) {
                send(guest, Map.of("type", "pong"));
                return;
            }
            if ("join".equals(type)) {
                join(guest, packet);
                return;
            }
            if (guest.name == null) {
                error(guest, "Hãy nhập tên để vào phòng.");
                return;
            }
            switch (type) {
                case "move" -> move(guest, packet);
                case "chat" -> chat(guest, packet, now);
                default -> error(guest, "Sự kiện không được hỗ trợ.");
            }
        } catch (JsonProcessingException exception) {
            error(guest, "Dữ liệu không hợp lệ.");
        }
    }

    private void join(Guest guest, JsonNode packet) throws IOException {
        if (guest.name != null) {
            return;
        }
        String name = clean(packet.path("name").asText());
        if (name.isBlank() || name.length() > ChatRoomLimits.NAME_LENGTH) {
            error(guest, "Tên cần từ 1 đến " + ChatRoomLimits.NAME_LENGTH + " ký tự.");
            return;
        }
        guest.name = name;
        String color = packet.path("color").asText();
        guest.color = COLORS.contains(color) ? color : "teal";
        int slot = (int) guests.values().stream().filter(g -> g.name != null).count() - 1;
        guest.x = map.layout().spawn().x() + (slot % 7 - 3) * 38;
        guest.y = map.layout().spawn().y() + (slot / 7 % 2) * 38;
        guest.lastMove = System.nanoTime();
        send(guest, Map.of("type", "welcome", "id", guest.session.getId(), "players", guests.values().stream()
            .filter(g -> g.name != null).map(Guest::view).toList(), "history", history));
        broadcast(Map.of("type", "joined", "player", guest.view()));
    }

    private void move(Guest guest, JsonNode packet) throws IOException {
        if (!packet.path("x").isNumber() || !packet.path("y").isNumber()) {
            error(guest, "Tọa độ không hợp lệ.");
            return;
        }
        double x = packet.path("x").asDouble();
        double y = packet.path("y").asDouble();
        long now = System.nanoTime();
        guest.allowance = Math.min(50, guest.allowance + (now - guest.lastMove) / 1e9 * map.layout().speed());
        guest.lastMove = now;
        double distance = Math.hypot(x - guest.x, y - guest.y);
        if (distance > guest.allowance || !map.clearPath(guest.x, guest.y, x, y)) {
            send(guest, Map.of("type", "correction", "x", guest.x, "y", guest.y));
            return;
        }
        guest.allowance -= distance;
        guest.x = x;
        guest.y = y;
        broadcast(Map.of("type", "moved", "id", guest.session.getId(), "x", x, "y", y));
    }

    private void chat(Guest guest, JsonNode packet, long now) throws IOException {
        String text = clean(packet.path("text").asText());
        if (text.isBlank() || text.length() > ChatRoomLimits.MESSAGE_LENGTH) {
            error(guest, "Tin nhắn cần từ 1 đến " + ChatRoomLimits.MESSAGE_LENGTH + " ký tự.");
            return;
        }
        if (now - guest.lastChat < ChatRoomLimits.CHAT_INTERVAL_MILLIS) {
            error(guest, "Bạn gửi quá nhanh. Hãy đợi một chút.");
            return;
        }
        guest.lastChat = now;
        var event = Map.<String, Object>of("type", "chat", "id", UUID.randomUUID().toString(),
            "playerId", guest.session.getId(), "name", guest.name, "color", guest.color,
            "text", text, "at", Instant.ofEpochMilli(now).toString());
        history.addLast(event);
        while (history.size() > ChatRoomLimits.HISTORY_SIZE) {
            history.removeFirst();
        }
        broadcast(event);
    }

    private String clean(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("[\\p{Cc}\\p{Cf}]", " ").strip();
    }

    private void error(Guest guest, String message) throws IOException {
        send(guest, Map.of("type", "error", "message", message));
    }

    private void send(Guest guest, Object event) throws IOException {
        if (guest.session.isOpen()) {
            guest.session.sendMessage(new TextMessage(json.writeValueAsString(event)));
        }
    }

    private void broadcast(Object event) throws IOException {
        String payload = json.writeValueAsString(event);
        for (Guest guest : List.copyOf(guests.values())) {
            if (guest.name != null && guest.session.isOpen()) {
                try {
                    guest.session.sendMessage(new TextMessage(payload));
                } catch (IOException | IllegalStateException exception) {
                    log.debug("Chat room send failed; closing connection");
                    close(guest, CloseStatus.SERVER_ERROR);
                }
            }
        }
    }

    public synchronized void disconnect(WebSocketSession session) throws IOException {
        Guest guest = guests.remove(session.getId());
        if (guest != null && guest.name != null) {
            broadcast(Map.of("type", "left", "id", session.getId(), "name", guest.name));
        }
    }

    /** Kiểm tra lại status/auth_ver định kỳ, tránh giữ quyền sau khi logout/khóa tài khoản. */
    @Scheduled(fixedDelayString = "${app.chat-room.session-check-interval}")
    public void sweep() {
        List<Guest> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(guests.values());
        }
        for (Guest guest : snapshot) {
            long now = System.currentTimeMillis();
            if (now - guest.lastPacket > properties.idleTimeout().toMillis()
                || (guest.name == null && now - guest.connectedAt > properties.ticketTtl().toMillis())) {
                close(guest, CloseStatus.GOING_AWAY);
                continue;
            }
            try {
                tickets.validate(guest.access);
            } catch (BusinessException exception) {
                close(guest, CloseStatus.POLICY_VIOLATION);
            } catch (RuntimeException exception) {
                log.warn("Chat room session validation unavailable; closing connection");
                close(guest, CloseStatus.SERVER_ERROR);
            }
        }
    }

    private void close(Guest guest, CloseStatus status) {
        try {
            guest.session.close(status);
        } catch (IOException exception) {
            log.debug("Chat room connection already closed");
        }
    }

}
