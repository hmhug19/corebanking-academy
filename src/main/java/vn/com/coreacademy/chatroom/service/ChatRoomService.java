package vn.com.coreacademy.chatroom.service;

import java.io.IOException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

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
    private static final int STARTING_DICE_TOKENS = 100;
    private static final long MAX_SAFE_DICE_BALANCE = 9_007_199_254_740_991L;
    private static final SecureRandom DICE_RANDOM = new SecureRandom();
    private static final Set<String> COLORS = Set.of("teal", "slate", "amber", "blue", "rose");
    private static final List<String> FACINGS = List.of("east", "southeast", "south", "southwest",
        "west", "northwest", "north", "northeast");
    private static final List<Question> QUESTIONS = List.of(
        new Question("Hành tinh nào được gọi là Hành tinh Đỏ?",
            List.of("Sao Kim", "Sao Hỏa", "Sao Thổ", "Sao Mộc"), 1),
        new Question("Một giờ rưỡi có bao nhiêu phút?",
            List.of("60 phút", "75 phút", "90 phút", "120 phút"), 2),
        new Question("Phím tắt nào thường dùng để dán nội dung trên Windows?",
            List.of("Ctrl + V", "Ctrl + C", "Ctrl + Z", "Ctrl + S"), 0));

    private final ObjectMapper json;
    private final ChatRoomMap map;
    private final ChatRoomTickets tickets;
    private final ChatRoomProperties properties;
    private final Map<String, Guest> guests = new LinkedHashMap<>();
    private final Map<String, HighFiveInvite> invitations = new LinkedHashMap<>();
    private String hostId;
    private Round round;
    private int nextQuestion;

    private record Question(String prompt, List<String> options, int correctIndex) {
    }

    private record HighFiveInvite(String id, Guest from, Guest to, long expiresAt) {
    }

    private record DiceResult(String side, long stake, List<Integer> dice, int total, String outcome, boolean won) {
    }

    private static final class Round {
        private final String id = UUID.randomUUID().toString();
        private final Question question;
        private final Map<String, Integer> answers = new LinkedHashMap<>();
        private boolean closed;

        private Round(Question question) {
            this.question = question;
        }
    }

    private static class Guest {
        private final WebSocketSession session;
        private final ChatRoomTickets.Access access;
        private final long connectedAt = System.currentTimeMillis();
        private volatile String name;
        private String color;
        private String avatar;
        private String facing = "north";
        private boolean activities;
        private boolean gestures;
        private boolean interactions;
        private boolean diceEnabled;
        private long diceBalance = STARTING_DICE_TOKENS;
        private long diceTurn;
        private DiceResult diceResult;
        private Integer seat;
        private long highFiveUntil;
        private double x;
        private double y;
        private double allowance = 30;
        private long lastMove = System.nanoTime();
        private long lastChat;
        private long lastGesture;
        private long packetWindow = System.currentTimeMillis();
        private volatile long lastPacket = System.currentTimeMillis();
        private int packets;

        private Guest(WebSocketSession session, ChatRoomTickets.Access access, int sendTimeoutMillis) {
            this.session = new ConcurrentWebSocketSessionDecorator(session, sendTimeoutMillis,
                ChatRoomLimits.OUTBOUND_BUFFER_BYTES);
            this.access = access;
        }

        private Map<String, Object> view() {
            Map<String, Object> player = new LinkedHashMap<>(Map.of("id", session.getId(), "name", name,
                "color", color, "avatar", avatar, "x", x, "y", y, "facing", facing));
            if (seat != null) {
                player.put("seat", seat);
            }
            return player;
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
                case "gesture" -> gesture(guest, packet, now);
                case "seat" -> seat(guest, packet, now);
                case "high-five-invite" -> inviteHighFive(guest, now);
                case "high-five-reply" -> replyHighFive(guest, packet, now);
                case "dice-roll" -> rollDice(guest, packet);
                case "game-start", "game-answer", "game-close" -> play(guest, type, packet);
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
        guest.activities = packet.path("activities").isBoolean() && packet.path("activities").asBoolean();
        guest.gestures = packet.path("gestures").isBoolean() && packet.path("gestures").asBoolean();
        guest.interactions = packet.path("interactions").isBoolean() && packet.path("interactions").asBoolean();
        guest.diceEnabled = packet.path("dice").isBoolean() && packet.path("dice").asBoolean()
            && map.layout().diceArea() != null;
        guest.avatar = "female".equals(packet.path("avatar").asText()) ? "female" : "male";
        String color = packet.path("color").asText();
        guest.color = COLORS.contains(color) ? color : "teal";
        ChatRoomMap.Point spawn = spawnFor(guest);
        guest.x = spawn.x();
        guest.y = spawn.y();
        guest.lastMove = System.nanoTime();
        send(guest, Map.of("type", "welcome", "id", guest.session.getId(), "players", guests.values().stream()
            .filter(g -> g.name != null).map(Guest::view).toList(), "history", List.of(), "gestures", guest.gestures,
            "interactions", guest.interactions));
        if (guest.diceEnabled) {
            sendDiceState(guest);
        }
        broadcast(Map.of("type", "joined", "player", guest.view()));
        if (guest.activities) {
            if (hostId == null) {
                hostId = guest.session.getId();
                broadcastGame();
            } else {
                send(guest, gameView(guest));
            }
        }
    }

    private void rollDice(Guest guest, JsonNode packet) throws IOException {
        if (!guest.diceEnabled) {
            return;
        }
        JsonNode turn = packet.path("turn");
        if (!turn.isIntegralNumber() || !turn.canConvertToLong() || turn.longValue() != guest.diceTurn) {
            sendDiceState(guest);
            return;
        }
        if (!map.layout().diceArea().contains(guest.x, guest.y)) {
            send(guest, Map.of("type", "dice-error", "message", "Hãy đến khu Tài/Xỉu để tung súc xắc."));
            return;
        }
        String side = packet.path("side").asText();
        JsonNode amount = packet.path("stake");
        if (!("small".equals(side) || "big".equals(side)) || !amount.isIntegralNumber()
            || !amount.canConvertToLong() || amount.longValue() < 1 || amount.longValue() > guest.diceBalance) {
            send(guest, Map.of("type", "dice-error", "message", "Chọn cửa và số token nguyên trong số dư hiện có."));
            return;
        }
        long stake = amount.longValue();
        if (stake > MAX_SAFE_DICE_BALANCE - guest.diceBalance) {
            send(guest, Map.of("type", "dice-error", "message", "Hãy đặt ít token hơn."));
            return;
        }
        int first = DICE_RANDOM.nextInt(6) + 1;
        int second = DICE_RANDOM.nextInt(6) + 1;
        int third = DICE_RANDOM.nextInt(6) + 1;
        int total = first + second + third;
        String outcome = first == second && second == third ? "triple" : (total <= 10 ? "small" : "big");
        boolean won = side.equals(outcome);
        guest.diceBalance += won ? stake : -stake;
        guest.diceTurn++;
        guest.diceResult = new DiceResult(side, stake, List.of(first, second, third), total, outcome, won);
        sendDiceState(guest);
    }

    private void sendDiceState(Guest guest) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>(Map.of("type", "dice-state", "balance", guest.diceBalance,
            "turn", guest.diceTurn));
        if (guest.diceResult != null) {
            state.put("result", guest.diceResult);
        }
        send(guest, state);
    }

    private ChatRoomMap.Point spawnFor(Guest joining) {
        ChatRoomMap.Point center = map.layout().spawn();
        ChatRoomMap.Point best = center;
        double bestClearance = -1;
        // Try the center of the entrance first, then spread out in both directions.
        for (int row : new int[] {-44, 48}) {
            for (int column : new int[] {0, 1, -1, 2, -2, 3, -3, 4, -4}) {
                double x = center.x() + column * 104;
                double y = center.y() + row;
                if (!map.walkable(x, y)) {
                    continue;
                }
                double clearance = guests.values().stream().filter(g -> g != joining && g.name != null)
                    .mapToDouble(g -> Math.hypot(g.x - x, g.y - y)).min().orElse(Double.POSITIVE_INFINITY);
                if (clearance >= 90) {
                    return new ChatRoomMap.Point(x, y);
                }
                if (clearance > bestClearance) {
                    best = new ChatRoomMap.Point(x, y);
                    bestClearance = clearance;
                }
            }
        }
        return best;
    }

    private void play(Guest guest, String type, JsonNode packet) throws IOException {
        if (!guest.activities) {
            error(guest, "Hãy vào lại phòng để tham gia đố vui.");
            return;
        }
        if (!"game-answer".equals(type) && !guest.session.getId().equals(hostId)) {
            error(guest, "Chỉ host có thể mở câu hỏi hoặc công bố đáp án.");
            return;
        }
        if ("game-start".equals(type)) {
            if (round != null && !round.closed) {
                error(guest, "Hãy công bố đáp án của câu hiện tại trước.");
                return;
            }
            round = new Round(QUESTIONS.get(nextQuestion));
            nextQuestion = (nextQuestion + 1) % QUESTIONS.size();
        } else {
            if (round == null || round.closed || !round.id.equals(packet.path("roundId").asText())) {
                error(guest, "Câu hỏi này đã đóng hoặc không còn hiệu lực.");
                return;
            }
            if ("game-close".equals(type)) {
                round.closed = true;
            } else {
                JsonNode choice = packet.path("choice");
                if (!choice.isIntegralNumber() || !choice.canConvertToInt()
                    || choice.intValue() < 0 || choice.intValue() >= round.question.options().size()) {
                    error(guest, "Hãy chọn một đáp án hợp lệ.");
                    return;
                }
                if (round.answers.putIfAbsent(guest.access.userId(), choice.intValue()) != null) {
                    send(guest, gameView(guest));
                    return;
                }
            }
        }
        broadcastGame();
    }

    private Map<String, Object> gameView(Guest guest) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "game");
        event.put("hostId", hostId);
        if (round != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("id", round.id);
            details.put("prompt", round.question.prompt());
            details.put("options", round.question.options());
            details.put("answered", round.answers.size());
            details.put("yourChoice", round.answers.get(guest.access.userId()));
            if (round.closed) {
                details.put("correctIndex", round.question.correctIndex());
                details.put("correctCount", round.answers.values().stream()
                    .filter(choice -> choice == round.question.correctIndex()).count());
            }
            event.put("round", details);
        }
        return event;
    }

    private void broadcastGame() throws IOException {
        for (Guest guest : List.copyOf(guests.values())) {
            if (guest.activities) {
                try {
                    send(guest, gameView(guest));
                } catch (IOException | IllegalStateException exception) {
                    log.debug("Chat room game update failed; closing connection");
                    close(guest, CloseStatus.SERVER_ERROR);
                }
            }
        }
    }

    private void move(Guest guest, JsonNode packet) throws IOException {
        if (guest.seat != null || guest.highFiveUntil > System.currentTimeMillis()) {
            send(guest, Map.of("type", "correction", "x", guest.x, "y", guest.y));
            return;
        }
        if (!packet.path("x").isNumber() || !packet.path("y").isNumber()) {
            error(guest, "Tọa độ không hợp lệ.");
            return;
        }
        double x = packet.path("x").asDouble();
        double y = packet.path("y").asDouble();
        String facing = packet.path("facing").asText("");
        if (packet.has("facing") && !FACINGS.contains(facing)) {
            error(guest, "Hướng nhìn không hợp lệ.");
            return;
        }
        long now = System.nanoTime();
        guest.allowance = Math.min(50, guest.allowance + (now - guest.lastMove) / 1e9 * map.layout().speed());
        guest.lastMove = now;
        double distance = Math.hypot(x - guest.x, y - guest.y);
        if (distance > guest.allowance || !map.clearPath(guest.x, guest.y, x, y)) {
            send(guest, Map.of("type", "correction", "x", guest.x, "y", guest.y));
            return;
        }
        guest.allowance -= distance;
        if (!facing.isEmpty()) {
            guest.facing = facing;
        } else if (distance > 0) {
            guest.facing = facingFrom(guest, x, y);
        }
        guest.x = x;
        guest.y = y;
        broadcastPosition(guest);
    }

    private void broadcastPosition(Guest guest) throws IOException {
        Map<String, Object> position = new LinkedHashMap<>(Map.of("type", "moved", "id", guest.session.getId(),
            "x", guest.x, "y", guest.y, "facing", guest.facing));
        if (guest.interactions) {
            position.put("seat", guest.seat);
        }
        broadcast(position);
    }

    private void seat(Guest guest, JsonNode packet, long now) throws IOException {
        if (!guest.interactions || guest.highFiveUntil > now) {
            return;
        }
        if ("stand".equals(packet.path("action").asText())) {
            if (guest.seat != null) {
                guest.seat = null;
                broadcastPosition(guest);
            }
            return;
        }
        if (!"sit".equals(packet.path("action").asText()) || guest.seat != null) {
            return;
        }
        JsonNode requested = packet.get("chair");
        if (requested != null && (!requested.isIntegralNumber() || !requested.canConvertToInt())) {
            error(guest, "Ghế không hợp lệ.");
            return;
        }
        Integer chair = requested == null ? null : requested.intValue();
        if (chair == null) {
            double nearest = ChatRoomLimits.SEAT_RANGE_PIXELS;
            for (int i = 0; i < map.layout().objects().size(); i++) {
                ChatRoomMap.Point point = freeSeatApproach(guest, i);
                if (point != null && Math.hypot(point.x() - guest.x, point.y() - guest.y) < nearest) {
                    nearest = Math.hypot(point.x() - guest.x, point.y() - guest.y);
                    chair = i;
                }
            }
        }
        ChatRoomMap.Point point = chair == null ? null : freeSeatApproach(guest, chair);
        if (point == null) {
            error(guest, "Hãy đến gần một ghế trống để ngồi.");
            return;
        }
        guest.seat = chair;
        guest.x = point.x();
        guest.y = point.y();
        guest.facing = "north";
        broadcastPosition(guest);
    }

    private ChatRoomMap.Point freeSeatApproach(Guest guest, int index) {
        if (index < 0 || index >= map.layout().objects().size()
            || guests.values().stream().anyMatch(other -> Integer.valueOf(index).equals(other.seat))) {
            return null;
        }
        ChatRoomMap.Furniture chair = map.layout().objects().get(index);
        if (!"chair".equals(chair.kind())) {
            return null;
        }
        double x = chair.x() + chair.w() / 2;
        double y = chair.y() + chair.h() + map.layout().radius() + 8;
        return Math.hypot(x - guest.x, y - guest.y) <= ChatRoomLimits.SEAT_RANGE_PIXELS
            && map.clearPath(guest.x, guest.y, x, y) ? new ChatRoomMap.Point(x, y) : null;
    }

    private void inviteHighFive(Guest guest, long now) throws IOException {
        invitations.values().removeIf(invite -> invite.expiresAt() <= now);
        if (!canHighFive(guest, now) || hasInvitation(guest)) {
            return;
        }
        Guest target = nearbyGuest(guest, ChatRoomLimits.HIGH_FIVE_RANGE_PIXELS,
            other -> canHighFive(other, now) && !hasInvitation(other)
                && Math.hypot(other.x - guest.x, other.y - guest.y) >= ChatRoomLimits.HIGH_FIVE_MIN_PIXELS);
        if (target == null) {
            error(guest, "Hãy đứng gần một bạn để mời đập tay.");
            return;
        }
        String id = UUID.randomUUID().toString();
        invitations.put(id, new HighFiveInvite(id, guest, target, now + ChatRoomLimits.HIGH_FIVE_INVITE_MILLIS));
        Map<String, Object> event = Map.of("type", "high-five-invite", "id", id,
            "from", guest.session.getId(), "to", target.session.getId());
        broadcast(event, viewer -> viewer == guest || viewer == target);
    }

    private boolean canHighFive(Guest guest, long now) {
        return guest.interactions && guest.seat == null && guest.highFiveUntil <= now && guest.session.isOpen();
    }

    private boolean hasInvitation(Guest guest) {
        return invitations.values().stream().anyMatch(invite -> invite.from() == guest || invite.to() == guest);
    }

    private void replyHighFive(Guest guest, JsonNode packet, long now) throws IOException {
        HighFiveInvite invite = invitations.get(packet.path("inviteId").asText());
        if (invite == null || invite.to() != guest || !packet.path("accept").isBoolean()) {
            return;
        }
        invitations.remove(invite.id());
        Guest sender = invite.from();
        broadcast(Map.of("type", "high-five-end", "id", invite.id()), viewer -> viewer == sender || viewer == guest);
        if (!packet.path("accept").asBoolean() || invite.expiresAt() <= now) {
            return;
        }
        double distance = Math.hypot(sender.x - guest.x, sender.y - guest.y);
        if (!canHighFive(sender, now) || !canHighFive(guest, now)
            || distance < ChatRoomLimits.HIGH_FIVE_MIN_PIXELS || distance > ChatRoomLimits.HIGH_FIVE_RANGE_PIXELS
            || !map.clearPath(sender.x, sender.y, guest.x, guest.y)) {
            error(guest, "Hai bạn cần đứng gần nhau để đập tay.");
            error(sender, "Hai bạn cần đứng gần nhau để đập tay.");
            return;
        }
        sender.highFiveUntil = guest.highFiveUntil = now + ChatRoomLimits.HIGH_FIVE_LOCK_MILLIS;
        broadcast(Map.of("type", "high-five", "from", posePosition(sender), "to", posePosition(guest)),
            viewer -> viewer.interactions);
    }

    private Map<String, Object> posePosition(Guest guest) {
        return Map.of("id", guest.session.getId(), "x", guest.x, "y", guest.y);
    }

    private void gesture(Guest guest, JsonNode packet, long now) throws IOException {
        String kind = packet.path("kind").asText();
        if (!guest.gestures || !("jump".equals(kind) || "wave".equals(kind))) {
            error(guest, "Động tác này không được hỗ trợ.");
            return;
        }
        if (now - guest.lastGesture < ChatRoomLimits.GESTURE_INTERVAL_MILLIS || guest.highFiveUntil > now
            || (guest.seat != null && "jump".equals(kind))) {
            return;
        }
        guest.lastGesture = now;
        Map<String, Object> event = new LinkedHashMap<>(Map.of("type", "gesture", "kind", kind,
            "playerId", guest.session.getId()));
        if ("wave".equals(kind)) {
            Guest target = nearbyGuest(guest, ChatRoomLimits.WAVE_RANGE_PIXELS, candidate -> true);
            if (target != null) {
                event.put("targetId", target.session.getId());
                event.put("facing", facingFrom(guest, target.x, target.y));
            }
        }
        broadcast(event, viewer -> viewer.gestures);
    }

    private Guest nearbyGuest(Guest actor, double range, Predicate<Guest> eligible) {
        Guest nearest = null;
        for (Guest candidate : guests.values()) {
            if (candidate == actor || candidate.name == null || !candidate.session.isOpen()) {
                continue;
            }
            double distance = Math.hypot(candidate.x - actor.x, candidate.y - actor.y);
            if (distance < range && eligible.test(candidate) && map.clearPath(actor.x, actor.y, candidate.x, candidate.y)) {
                range = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private String facingFrom(Guest actor, double x, double y) {
        if (x == actor.x && y == actor.y) {
            return actor.facing;
        }
        int octant = Math.floorMod((int) Math.round(Math.atan2(y - actor.y, x - actor.x) / (Math.PI / 4)), 8);
        return FACINGS.get(octant);
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
        broadcast(event, guest -> true);
    }

    private void broadcast(Object event, Predicate<Guest> audience) throws IOException {
        String payload = json.writeValueAsString(event);
        for (Guest guest : List.copyOf(guests.values())) {
            if (guest.name != null && guest.session.isOpen() && audience.test(guest)) {
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
            invitations.values().removeIf(invite -> invite.from() == guest || invite.to() == guest);
            broadcast(Map.of("type", "left", "id", session.getId(), "name", guest.name));
            if (session.getId().equals(hostId)) {
                hostId = guests.values().stream()
                    .filter(candidate -> candidate.activities && candidate.session.isOpen())
                    .map(candidate -> candidate.session.getId()).findFirst().orElse(null);
                if (hostId == null) {
                    round = null;
                    nextQuestion = 0;
                } else {
                    broadcastGame();
                }
            }
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
