package vn.com.coreacademy.chatroom.service;

import java.io.IOException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
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
import vn.com.coreacademy.chatroom.repository.ChatRoomGameRepository.CaroGame;
import vn.com.coreacademy.chatroom.service.ChatRoomMap.Point;
import vn.com.coreacademy.common.exception.BusinessException;

/** Phòng chung trong bộ nhớ của một instance; mọi thay đổi và broadcast giữ cùng thứ tự. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRoomService {
    private static final long CARO_INVITE_MILLIS = 20_000;
    private static final int APPROACH_TICK_MILLIS = 80;
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
    private final ChatRoomGames games;
    private final Map<String, Guest> guests = new LinkedHashMap<>();
    private final Map<String, HighFiveInvite> invitations = new LinkedHashMap<>();
    private final Map<String, CaroInvite> caroInvites = new LinkedHashMap<>();
    private long knownDiceRound;
    private long lastWalletRefresh;
    private String hostId;
    private Round round;
    private int nextQuestion;

    private record Question(String prompt, List<String> options, int correctIndex) {
    }

    private record HighFiveInvite(String id, Guest from, Guest to, long expiresAt) {
    }

    private record CaroInvite(String id, Guest from, Guest to, long stake, long expiresAt) {
    }

    private record Approach(Integer chair, Guest partner, Deque<Point> path) {
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
        private boolean roomGames;
        private boolean diceViewing;
        private long wallet = -1;
        private Integer seat;
        private Approach approach;
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
                case "approach-cancel" -> stopApproach(guest);
                case "dice-open", "dice-close", "dice-bet" -> dice(guest, type, packet);
                case "wallet-rank" -> {
                    if (guest.roomGames) {
                        send(guest, Map.of("type", "wallet-rank", "ranks", games.rank()));
                    }
                }
                case "caro-invite", "caro-reply", "caro-move" -> caro(guest, type, packet, now);
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
        long balance = games.claim(guest.access.userId());
        guest.name = name;
        guest.activities = packet.path("activities").isBoolean() && packet.path("activities").asBoolean();
        guest.gestures = packet.path("gestures").isBoolean() && packet.path("gestures").asBoolean();
        guest.interactions = packet.path("interactions").isBoolean() && packet.path("interactions").asBoolean();
        guest.roomGames = packet.path("roomGames").isBoolean() && packet.path("roomGames").asBoolean();
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
        if (guest.roomGames) {
            sendWallet(guest, balance);
            CaroGame active = games.activeCaro(guest.access.userId()).orElse(null);
            if (active != null && active.expiresAt().isAfter(Instant.now())) {
                sendCaro(guest, active);
            }
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

    private void dice(Guest guest, String type, JsonNode packet) throws IOException {
        if (!guest.roomGames || map.layout().diceArea() == null) {
            return;
        }
        if ("dice-close".equals(type)) {
            guest.diceViewing = false;
            return;
        }
        if (!map.layout().diceArea().contains(guest.x, guest.y)) {
            send(guest, Map.of("type", "dice-error", "message", "Hãy đến bàn Trên/Dưới để chơi."));
            return;
        }
        if ("dice-open".equals(type)) {
            guest.diceViewing = true;
            publishGames(true);
            return;
        }
        if (!guest.diceViewing) {
            return;
        }
        boolean accepted = games.bet(guest.access.userId(), integer(packet, "round"), packet.path("side").asText(),
            integer(packet, "stake"));
        publishGames(true);
        refreshWallets();
        if (!accepted) {
            send(guest, Map.of("type", "dice-error", "message",
                "Không đặt được cược. Hãy kiểm tra số dư và lượt đang mở."));
        }
    }

    @Scheduled(fixedDelay = 1000)
    public synchronized void gameTick() throws IOException {
        publishGames(false);
        long now = System.currentTimeMillis();
        if (now - lastWalletRefresh >= 5000) {
            refreshWallets();
            lastWalletRefresh = now;
        }
        expireCaroInvites(now);
    }

    private void publishGames(boolean refreshDice) throws IOException {
        boolean watching = guests.values().stream().anyMatch(guest -> guest.diceViewing && guest.session.isOpen());
        ChatRoomGames.GameTick tick = games.tick(watching, knownDiceRound, refreshDice);
        for (CaroGame game : tick.expired()) {
            publishCaro(game);
        }
        ChatRoomGames.DiceTable table = tick.dice();
        if (table == null) {
            return;
        }
        knownDiceRound = table.round().id();
        for (Guest guest : List.copyOf(guests.values())) {
            if (!guest.diceViewing) {
                continue;
            }
            Map<String, Object> state = new LinkedHashMap<>(Map.of("type", "dice-state", "round", knownDiceRound,
                "closesAt", table.round().closesAt().toEpochMilli(), "serverTime", System.currentTimeMillis(),
                "history", table.history()));
            var ownBet = table.bets().stream().filter(bet -> bet.userId().equals(guest.access.userId())).findFirst();
            ownBet.ifPresent(bet -> state.put("bet", Map.of("side", bet.side(), "stake", bet.stake())));
            var lastBet = table.lastBets().stream().filter(bet -> bet.userId().equals(guest.access.userId())).findFirst();
            lastBet.ifPresent(bet -> state.put("lastBet", Map.of("side", bet.side(), "stake", bet.stake())));
            sendGame(guest, state);
        }
    }

    private void refreshWallets() {
        Set<String> ids = new HashSet<>();
        List<Guest> players = guests.values().stream().filter(guest -> guest.roomGames && guest.session.isOpen()).toList();
        players.forEach(guest -> ids.add(guest.access.userId()));
        Map<String, Long> balances = games.balances(ids);
        players.forEach(guest -> sendWallet(guest, balances.getOrDefault(guest.access.userId(), 0L)));
    }

    private void sendWallet(Guest guest, long balance) {
        if (guest.wallet != balance) {
            guest.wallet = balance;
            sendGame(guest, Map.of("type", "wallet", "balance", balance));
        }
    }

    private void sendGame(Guest guest, Object event) {
        try {
            send(guest, event);
        } catch (IOException | IllegalStateException exception) {
            close(guest, CloseStatus.SERVER_ERROR);
        }
    }

    private void caro(Guest guest, String type, JsonNode packet, long now) throws IOException {
        if (!guest.roomGames) {
            return;
        }
        expireCaroInvites(now);
        switch (type) {
            case "caro-invite" -> inviteCaro(guest, packet, now);
            case "caro-reply" -> replyCaro(guest, packet);
            case "caro-move" -> {
                long cell = integer(packet, "cell");
                long move = integer(packet, "move");
                if (cell >= 0 && cell < ChatRoomGames.MAX_CARO_CELLS
                    && move >= 0 && move < ChatRoomGames.MAX_CARO_CELLS) {
                    games.playCaro(guest.access.userId(), packet.path("gameId").asText(), (int) move, (int) cell)
                        .ifPresent(game -> {
                            publishCaro(game);
                            if (game.result() != null) {
                                refreshWallets();
                            }
                        });
                }
            }
            default -> { }
        }
    }

    private void inviteCaro(Guest guest, JsonNode packet, long now) throws IOException {
        Guest target = guests.get(packet.path("target").asText());
        long stake = integer(packet, "stake");
        if (!canInviteCaro(guest, target) || stake < 0 || stake > ChatRoomGames.MAX_STAKE
            || caroInvites.values().stream().anyMatch(invite -> involves(invite, guest) || involves(invite, target))
            || games.activeCaro(guest.access.userId()).isPresent()
            || games.activeCaro(target.access.userId()).isPresent()) {
            send(guest, Map.of("type", "caro-error", "message", "Người chơi này chưa thể nhận lời thách đấu."));
            return;
        }
        String id = UUID.randomUUID().toString();
        CaroInvite invite = new CaroInvite(id, guest, target, stake, now + CARO_INVITE_MILLIS);
        caroInvites.put(id, invite);
        broadcast(Map.of("type", "caro-invite", "id", id, "from", guest.session.getId(), "fromName", guest.name,
            "to", target.session.getId(), "toName", target.name, "stake", stake),
            viewer -> viewer == guest || viewer == target);
    }

    private boolean canInviteCaro(Guest from, Guest to) {
        return to != null && to.name != null && to.roomGames && to.session.isOpen()
            && !from.access.userId().equals(to.access.userId())
            && Math.hypot(from.x - to.x, from.y - to.y) <= ChatRoomLimits.HIGH_FIVE_RANGE_PIXELS
            && map.clearPath(from.x, from.y, to.x, to.y);
    }

    private void replyCaro(Guest guest, JsonNode packet) throws IOException {
        CaroInvite invite = caroInvites.get(packet.path("inviteId").asText());
        if (invite == null || invite.to() != guest || !packet.path("accept").isBoolean()) {
            return;
        }
        endCaroInvite(invite);
        if (!packet.path("accept").asBoolean()) {
            return;
        }
        Guest sender = invite.from();
        CaroGame game = canInviteCaro(sender, guest) && sender.session.isOpen()
            ? games.startCaro(participant(sender), participant(guest), invite.stake()).orElse(null) : null;
        if (game == null) {
            broadcast(Map.of("type", "caro-error", "message", "Không thể mở ván. Hãy kiểm tra khoảng cách và số dư."),
                viewer -> involves(invite, viewer));
            return;
        }
        publishCaro(game);
        refreshWallets();
    }

    private void expireCaroInvites(long now) {
        caroInvites.values().stream().filter(invite -> invite.expiresAt() <= now).toList().forEach(this::endCaroInvite);
    }

    private void endCaroInvite(CaroInvite invite) {
        caroInvites.remove(invite.id());
        Object event = Map.of("type", "caro-invite-end", "id", invite.id());
        sendGame(invite.from(), event);
        sendGame(invite.to(), event);
    }

    private static boolean involves(CaroInvite invite, Guest guest) {
        return invite.from().access.userId().equals(guest.access.userId())
            || invite.to().access.userId().equals(guest.access.userId());
    }

    private static ChatRoomGames.Participant participant(Guest guest) {
        return new ChatRoomGames.Participant(guest.access.userId(), guest.name);
    }

    private void publishCaro(CaroGame game) {
        for (Guest guest : List.copyOf(guests.values())) {
            if (guest.roomGames && (guest.access.userId().equals(game.xUser()) || guest.access.userId().equals(game.oUser()))) {
                sendCaro(guest, game);
            }
        }
    }

    private void sendCaro(Guest guest, CaroGame game) {
        Map<String, Object> state = new LinkedHashMap<>(Map.of("type", "caro-state", "id", game.id(),
            "xName", game.xName(), "oName", game.oName(), "piece", guest.access.userId().equals(game.xUser()) ? "X" : "O",
            "stake", game.stake(), "board", game.board(), "moves", game.moves(),
            "expiresAt", game.expiresAt().toEpochMilli(), "serverTime", System.currentTimeMillis()));
        if (game.result() != null) {
            state.put("result", game.result());
        }
        sendGame(guest, state);
    }

    private static long integer(JsonNode packet, String key) {
        JsonNode value = packet.path(key);
        return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : -1;
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
        if (guest.approach != null) {
            return;
        }
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
        if (guest.diceViewing && !map.layout().diceArea().contains(x, y)) {
            guest.diceViewing = false;
        }
        broadcastPosition(guest);
    }

    private void broadcastPosition(Guest guest) throws IOException {
        Map<String, Object> position = new LinkedHashMap<>(Map.of("type", "moved", "id", guest.session.getId(),
            "x", guest.x, "y", guest.y, "facing", guest.facing));
        if (guest.interactions) {
            position.put("seat", guest.seat);
            position.put("approaching", guest.approach != null);
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
            return;
        }
        Point origin = point(guest);
        List<Integer> chairs = java.util.stream.IntStream.range(0, map.layout().objects().size()).boxed()
            .filter(index -> requested == null || index == requested.intValue())
            .filter(index -> seatApproach(index) != null)
            .sorted(Comparator.comparingDouble(index -> ChatRoomMap.distance(origin, seatApproach(index)))).toList();
        for (int chair : chairs) {
            Point destination = seatApproach(chair);
            List<Point> path = map.path(origin, destination);
            if (path.size() == 1 && ChatRoomMap.distance(origin, destination) <= ChatRoomLimits.SEAT_RANGE_PIXELS) {
                guest.approach = null;
                takeSeat(guest, chair, destination);
                return;
            }
            if (startApproach(guest, chair, null, path)) {
                return;
            }
        }
    }

    private Point seatApproach(int index) {
        ChatRoomMap.Furniture chair = map.layout().objects().get(index);
        if (!"chair".equals(chair.kind())
            || guests.values().stream().anyMatch(other -> Integer.valueOf(index).equals(other.seat))) {
            return null;
        }
        return new Point(chair.x() + chair.w() / 2, chair.y() + chair.h() + map.layout().radius() + 8);
    }

    private void takeSeat(Guest guest, int chair, Point destination) throws IOException {
        guest.seat = chair;
        guest.x = destination.x();
        guest.y = destination.y();
        guest.facing = "north";
        broadcastPosition(guest);
    }

    private void inviteHighFive(Guest guest, long now) throws IOException {
        invitations.values().removeIf(invite -> invite.expiresAt() <= now);
        if (!canHighFive(guest, now) || hasInvitation(guest)) {
            return;
        }
        Point origin = point(guest);
        List<Guest> candidates = guests.values().stream()
            .filter(other -> other != guest && other.name != null && other.approach == null
                && canHighFive(other, now) && !hasInvitation(other))
            .sorted(Comparator.comparingDouble(other -> ChatRoomMap.distance(origin, point(other)))).toList();
        for (Guest target : candidates) {
            if (inHighFiveRange(guest, target)) {
                stopApproach(guest);
                sendHighFiveInvite(guest, target, now);
                return;
            }
            double angle = Math.atan2(guest.y - target.y, guest.x - target.x);
            for (int offset : new int[] {0, 1, -1, 2, -2, 3, -3, 4}) {
                double heading = angle + offset * Math.PI / 4;
                Point destination = new Point(target.x + 92 * Math.cos(heading), target.y + 92 * Math.sin(heading));
                if (map.clearPath(destination.x(), destination.y(), target.x, target.y)
                    && startApproach(guest, null, target, map.path(origin, destination))) {
                    return;
                }
            }
        }
    }

    private boolean startApproach(Guest guest, Integer chair, Guest partner, List<Point> path) throws IOException {
        if (path.isEmpty()) {
            return false;
        }
        guest.approach = new Approach(chair, partner, new ArrayDeque<>(path));
        guest.diceViewing = false;
        broadcastPosition(guest);
        return true;
    }

    private void stopApproach(Guest guest) throws IOException {
        if (guest.approach != null) {
            guest.approach = null;
            broadcastPosition(guest);
        }
    }

    @Scheduled(fixedRate = APPROACH_TICK_MILLIS)
    public synchronized void walkApproaches() throws IOException {
        long now = System.currentTimeMillis();
        for (Guest guest : List.copyOf(guests.values())) {
            Approach approach = guest.approach;
            if (approach == null || !guest.session.isOpen()) {
                continue;
            }
            Guest partner = approach.partner();
            if ((partner != null && (!canHighFive(partner, now) || hasInvitation(partner) || hasInvitation(guest)))
                || (approach.chair() != null && seatApproach(approach.chair()) == null)) {
                stopApproach(guest);
                continue;
            }
            Point next = approach.path().getFirst();
            double distance = ChatRoomMap.distance(point(guest), next);
            double stride = map.layout().speed() * APPROACH_TICK_MILLIS / 1000;
            guest.facing = facingFrom(guest, next.x(), next.y());
            if (distance > stride) {
                guest.x += (next.x() - guest.x) * stride / distance;
                guest.y += (next.y() - guest.y) * stride / distance;
            } else {
                guest.x = next.x();
                guest.y = next.y();
                approach.path().removeFirst();
            }
            if (approach.path().isEmpty() || (partner != null && inHighFiveRange(guest, partner))) {
                guest.approach = null;
                if (approach.chair() != null) {
                    takeSeat(guest, approach.chair(), point(guest));
                    continue;
                }
                if (inHighFiveRange(guest, partner)) {
                    sendHighFiveInvite(guest, partner, now);
                }
            }
            broadcastPosition(guest);
        }
    }

    private static Point point(Guest guest) {
        return new Point(guest.x, guest.y);
    }

    private boolean inHighFiveRange(Guest from, Guest to) {
        double distance = ChatRoomMap.distance(point(from), point(to));
        return distance >= ChatRoomLimits.HIGH_FIVE_MIN_PIXELS && distance <= ChatRoomLimits.HIGH_FIVE_RANGE_PIXELS
            && map.clearPath(from.x, from.y, to.x, to.y);
    }

    private void sendHighFiveInvite(Guest guest, Guest target, long now) throws IOException {
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
        if (!canHighFive(sender, now) || !canHighFive(guest, now)
            || !inHighFiveRange(sender, guest)) {
            return;
        }
        stopApproach(sender);
        stopApproach(guest);
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
            caroInvites.values().stream().filter(invite -> invite.from() == guest || invite.to() == guest)
                .toList().forEach(this::endCaroInvite);
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
