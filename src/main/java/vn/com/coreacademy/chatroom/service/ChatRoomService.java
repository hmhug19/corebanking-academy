package vn.com.coreacademy.chatroom.service;

import java.io.IOException;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import vn.com.coreacademy.chatroom.config.ChatRoomProperties;
import vn.com.coreacademy.chatroom.constants.ChatRoomLimits;
import vn.com.coreacademy.chatroom.service.ChatRoomMap.Point;
import vn.com.coreacademy.common.exception.BusinessException;

/** Phòng chung trong bộ nhớ của một instance; mọi thay đổi và broadcast giữ cùng thứ tự. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRoomService {
    private static final long CARO_INVITE_MILLIS = 20_000;
    private static final int APPROACH_TICK_MILLIS = 80;
    private static final double APPROACH_SPEED_MULTIPLIER = 2.4;
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
    private final Map<Integer, CaroTableSession> caroTables = new LinkedHashMap<>();
    private final Map<String, ChatRoomCaro> caroMatches = new LinkedHashMap<>();
    private final Deque<Map<String, Object>> chatHistory = new ArrayDeque<>();
    private final Map<String, BoardSession> boardSessions = new LinkedHashMap<>();
    private final RoomColorFloor colorFloor = new RoomColorFloor();
    private String mediaId = UUID.randomUUID().toString();
    private String mediaSource = "idle";
    private String mediaTitle = "";
    private String mediaVideoId = "";
    private double mediaPosition;
    private boolean mediaPaused = true;
    private long mediaUpdatedAt;
    @Value("${app.chat-room.media-ice-urls:stun:stun.l.google.com:19302}")
    private String mediaIceUrls = "stun:stun.l.google.com:19302";
    @Value("${app.chat-room.media-turn-username:}")
    private String mediaTurnUsername = "";
    @Value("${app.chat-room.media-turn-credential:}")
    private String mediaTurnCredential = "";
    private static final Set<String> REACTIONS = Set.of("hello", "laugh", "clap", "love", "celebrate", "surprise");
    private long knownDiceRound = -1;
    private long diceClosesAt;
    private long lastWalletRefresh;
    private long gameRetryAt;
    private String hostId;
    private Round round;
    private int nextQuestion;

    private record Question(String prompt, List<String> options, int correctIndex) {
    }

    private record HighFiveInvite(String id, Guest from, Guest to, long expiresAt) {
    }

    private record CaroInvite(String id, Guest from, Guest to, long stake, long expiresAt) {
    }

    private static final class CaroTableSession {
        private Guest host;
        private Long stake;
        private CaroInvite invite;
    }

    private static final class BoardSession {
        private long points = 10;
        private final Set<String> ready = new HashSet<>();
        private RoomBoardMatch match;
    }

    private record Approach(Integer chair, Guest partner, Deque<Point> path, double speed) {
        private Map<String, Object> route(Guest guest) {
            List<Point> points = new ArrayList<>(path.size() + 1);
            points.add(point(guest));
            points.addAll(path);
            return Map.of("points", points, "speed", speed);
        }
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
        private boolean reactions;
        private boolean boardGames;
        private boolean floorGame;
        private boolean presentation;
        private String mediaWatchId;
        private long lastMediaCommand;
        private String watchingBoard;
        private long lastReaction;
        private long wallet = -1;
        private long shownWallet = -1;
        private UUID activeCaroId;
        private Integer seat;
        private Approach approach;
        private long lastApproachStep;
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
            if (approach != null) {
                player.put("approaching", true);
                player.put("route", approach.route(this));
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
            tickFloor(now);
            switch (type) {
                case "move" -> move(guest, packet);
                case "chat" -> chat(guest, packet, now);
                case "gesture" -> gesture(guest, packet, now);
                case "reaction" -> reaction(guest, packet, now);
                case "dice-approach" -> approachDice(guest);
                case "media-start", "media-stop", "media-control", "media-watch", "media-signal" ->
                    presentation(guest, type, packet, now);
                case "board-watch", "board-ready", "board-move", "board-action" -> board(guest, type, packet, now);
                case "floor-join" -> {
                    if (guest.floorGame && map.layout().colorFloor().contains(guest.x, guest.y)
                        && guest.seat == null && colorFloor.join(guest.access.userId(), true)) {
                        publishFloor(now);
                    }
                }
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
                case "caro-stake", "caro-reply", "caro-move" -> caro(guest, type, packet, now);
                case "game-start", "game-answer", "game-close" -> play(guest, type, packet);
                default -> error(guest, "Sự kiện không được hỗ trợ.");
            }
        } catch (JsonProcessingException exception) {
            error(guest, "Dữ liệu không hợp lệ.");
        } catch (DataAccessException | TransactionException exception) {
            gamesUnavailable(guest, exception);
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
        guest.presentation = packet.path("presentation").asBoolean(false);
        guest.activities = packet.path("activities").isBoolean() && packet.path("activities").asBoolean();
        guest.gestures = packet.path("gestures").isBoolean() && packet.path("gestures").asBoolean();
        guest.interactions = packet.path("interactions").isBoolean() && packet.path("interactions").asBoolean();
        guest.reactions = packet.path("reactions").asBoolean(false);
        guest.boardGames = packet.path("boardGames").asBoolean(false);
        guest.floorGame = false;
        boolean wantsGames = packet.path("roomGames").isBoolean() && packet.path("roomGames").asBoolean();
        guest.avatar = "female".equals(packet.path("avatar").asText()) ? "female" : "male";
        String color = packet.path("color").asText();
        guest.color = COLORS.contains(color) ? color : "teal";
        ChatRoomMap.Point spawn = spawnFor(guest);
        guest.x = spawn.x();
        guest.y = spawn.y();
        guest.lastMove = System.nanoTime();
        send(guest, Map.of("type", "welcome", "id", guest.session.getId(), "players", guests.values().stream()
            .filter(g -> g.name != null).map(Guest::view).toList(), "history", List.copyOf(chatHistory), "gestures", guest.gestures,
            "interactions", guest.interactions));
        broadcast(Map.of("type", "joined", "player", guest.view()));
        if (guest.activities) {
            if (hostId == null) {
                hostId = guest.session.getId();
                broadcastGame();
            } else {
                send(guest, gameView(guest));
            }
        }
        initializeGames(guest, wantsGames);
        if (guest.presentation) {
            sendMediaState(guest);
        }
        if (guest.reactions || guest.boardGames || guest.floorGame) {
            sendGame(guest, Map.of("type", "playground-ready", "reactions", guest.reactions,
                "boardGames", guest.boardGames, "floorGame", guest.floorGame));
        }
        if (guest.boardGames) {
            sendBoardTables(guest);
            for (var entry : boardSessions.entrySet()) {
                RoomBoardMatch match = entry.getValue().match;
                if (match != null && match.side(guest.access.userId()) >= 0 && match.reason == null) {
                    guest.watchingBoard = entry.getKey();
                    sendBoard(guest, entry.getKey(), entry.getValue(), System.currentTimeMillis());
                    break;
                }
            }
        }
        if (guest.floorGame) {
            sendGame(guest, colorFloor.snapshot(guest.access.userId(), System.currentTimeMillis()));
        }
    }

    private void initializeGames(Guest guest, boolean wantsGames) throws IOException {
        try {
            long balance = games.claim(guest.access.userId());
            if (!wantsGames) {
                return;
            }
            guest.roomGames = true;
            sendWallet(guest, balance);
            long now = System.currentTimeMillis();
            ChatRoomCaro match = caroMatches.get(guest.access.userId());
            if (match != null) {
                if (match.expire(now) || match.presence(guest.access.userId(), true)) {
                    publishCaro(match);
                } else {
                    sendCaro(guest, match);
                }
            }
        } catch (DataAccessException | TransactionException exception) {
            if (wantsGames) {
                gamesUnavailable(guest, exception);
            } else {
                logGameFailure(exception);
            }
        }
    }

    private void gamesUnavailable(Guest guest, NestedRuntimeException exception) throws IOException {
        guest.roomGames = false;
        guest.diceViewing = false;
        if (map.caroTable(guest.seat) != 0) {
            leaveSeat(guest);
        }
        String sqlState = logGameFailure(exception);
        String message = switch (sqlState == null ? "" : sqlState) {
            case "42501" -> "Máy chủ thiếu quyền truy cập dữ liệu ví CPoint hoặc trò chơi. "
                + "Hãy báo quản trị viên. Bạn vẫn có thể sử dụng phòng.";
            case "42P01", "42703" -> "Máy chủ chưa cài đủ dữ liệu cho ví CPoint và trò chơi. "
                + "Hãy báo quản trị viên. Bạn vẫn có thể sử dụng phòng.";
            default -> "Máy chủ tạm thời không truy cập được ví CPoint và trò chơi. "
                + "Bạn vẫn có thể sử dụng phòng và vào lại để thử.";
        };
        send(guest, Map.of("type", "error", "scope", "games", "message", message));
    }

    private String logGameFailure(NestedRuntimeException exception) {
        Throwable cause = exception.getMostSpecificCause();
        String sqlState = cause instanceof SQLException sql ? sql.getSQLState() : null;
        long now = System.currentTimeMillis();
        if (now >= gameRetryAt) {
            log.warn("Chat room game storage unavailable (type={}, sqlState={})",
                exception.getClass().getSimpleName(), sqlState);
            gameRetryAt = now + 10_000;
        }
        return sqlState;
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
            integer(packet, "stake"), reserved(guest.access.userId()));
        publishGames(true);
        refreshWallets();
        if (!accepted) {
            send(guest, Map.of("type", "dice-error", "message",
                "Chưa gửi được CPoint. Hãy kiểm tra số dư và lượt đang mở."));
        }
    }

    @Scheduled(fixedDelay = 1000)
    public synchronized void gameTick() throws IOException {
        long now = System.currentTimeMillis();
        for (var entry : boardSessions.entrySet()) {
            BoardSession table = entry.getValue();
            if (table.match != null && table.match.expire(now)) {
                publishBoard(entry.getKey(), now);
            }
            if (table.match != null && table.match.settled && now - table.match.finishedAt > 120_000) {
                table.match = null;
                table.ready.clear();
                publishBoard(entry.getKey(), now);
            }
        }
        expireCaroInvites(now);
        for (ChatRoomCaro match : caroMatches.values().stream().distinct().toList()) {
            if (match.expire(now)) {
                publishCaro(match);
            }
        }
        caroMatches.values().removeIf(match -> match.settled && now - match.finishedAt > ChatRoomCaro.TURN_MILLIS);
        if (now < gameRetryAt) {
            return;
        }
        try {
            for (var entry : boardSessions.entrySet()) {
                RoomBoardMatch match = entry.getValue().match;
                if (match != null && match.reason != null && !match.settled) {
                    games.settleCaro(match.players[match.winner].userId(),
                        match.players[1 - match.winner].userId(), match.points, match.transfer);
                    match.settled = true;
                    refreshWallets();
                    publishBoard(entry.getKey(), now);
                }
            }
            for (ChatRoomCaro match : caroMatches.values().stream().distinct().toList()) {
                if (match.result != null && !match.settled) {
                    games.settleCaro(match.winner().id(), match.loser().id(), match.points, match.walletTransfer);
                    match.settled = true;
                    refreshWallets();
                }
            }
            publishGames(false);
            if (now - lastWalletRefresh >= 5000) {
                refreshWallets();
                lastWalletRefresh = now;
            }
        } catch (DataAccessException | TransactionException exception) {
            logGameFailure(exception);
        }
    }

    private void publishGames(boolean refreshDice) throws IOException {
        boolean watching = guests.values().stream().anyMatch(guest -> guest.diceViewing && guest.session.isOpen());
        if (!refreshDice && (diceClosesAt > System.currentTimeMillis() || !watching && knownDiceRound == 0)) {
            return;
        }
        ChatRoomGames.DiceTick tick = games.tickDice(watching, knownDiceRound, refreshDice);
        knownDiceRound = tick.openRound();
        diceClosesAt = tick.closesAt();
        ChatRoomGames.DiceTable table = tick.table();
        if (table == null) {
            return;
        }
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
        guest.wallet = balance;
        showWallet(guest);
    }

    private void showWallet(Guest guest) {
        long available = available(guest);
        if (guest.shownWallet != available) {
            guest.shownWallet = available;
            sendGame(guest, Map.of("type", "wallet", "balance", available));
        }
    }

    private long available(Guest guest) {
        return Math.max(0, guest.wallet - reserved(guest.access.userId()));
    }

    private long reserved(String userId) {
        ChatRoomCaro match = caroMatches.get(userId);
        long points = match != null && !match.settled ? match.points : 0;
        for (BoardSession board : boardSessions.values()) {
            if (board.match != null && !board.match.settled && board.match.side(userId) >= 0) {
                points += board.match.points;
            }
        }
        return points;
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
        int number = map.caroTable(guest.seat);
        CaroTableSession table = caroTables.get(number);
        if (table == null) {
            return;
        }
        Guest partner = caroPartner(guest, number);
        switch (type) {
            case "caro-stake" -> {
                long stake = integer(packet, "stake");
                if (table.host == guest && table.invite == null && guest.activeCaroId == null
                    && (partner == null || partner.activeCaroId == null)) {
                    if (stake < ChatRoomGames.MIN_CARO_STAKE) {
                        sendGame(guest, Map.of("type", "caro-error", "message",
                            "Mỗi bạn góp ít nhất " + ChatRoomGames.MIN_CARO_STAKE + " CPoint nhé."));
                        return;
                    }
                    if (stake > ChatRoomGames.MAX_STAKE || stake > available(guest)) {
                        sendGame(guest, Map.of("type", "caro-error", "message",
                            "Số CPoint góp vượt giới hạn hoặc số dư của bạn."));
                        return;
                    }
                    table.stake = stake;
                    refreshCaroTable(number, now);
                }
            }
            case "caro-reply" -> replyCaro(guest, table, packet, now);
            case "caro-move" -> {
                long cell = integer(packet, "cell");
                long move = integer(packet, "move");
                if (atSameGame(guest, partner)) {
                    ChatRoomCaro match = caroMatches.get(guest.access.userId());
                    if (match != null && (match.expire(now) || match.play(guest.access.userId(),
                        packet.path("gameId").asText(), move, cell, now))) {
                        publishCaro(match);
                    }
                }
            }
            default -> { }
        }
    }

    private Guest caroPartner(Guest guest, int table) {
        return guests.values().stream()
            .filter(other -> other != guest && atCaroTable(other, table))
            .findFirst().orElse(null);
    }

    private boolean atCaroTable(Guest guest, int table) {
        return guest.roomGames && guest.session.isOpen() && map.caroTable(guest.seat) == table;
    }

    private static boolean atSameGame(Guest guest, Guest partner) {
        return partner != null && guest.activeCaroId != null && guest.activeCaroId.equals(partner.activeCaroId);
    }

    private void refreshCaroTable(int number, long now) {
        CaroTableSession table = caroTables.computeIfAbsent(number, ignored -> new CaroTableSession());
        if (table.host == null || !atCaroTable(table.host, number) || !guests.containsValue(table.host)) {
            endCaroInvite(table);
            table.host = guests.values().stream().filter(guest -> atCaroTable(guest, number)).findFirst().orElse(null);
            table.stake = null;
        }
        Guest host = table.host;
        if (host == null) {
            caroTables.remove(number);
            return;
        }
        Guest partner = caroPartner(host, number);
        if (table.invite != null && table.invite.to() != partner) {
            endCaroInvite(table);
        }
        boolean playing = atSameGame(host, partner);
        boolean paused = !playing && (host.activeCaroId != null || (partner != null && partner.activeCaroId != null));
        sendCaroTable(host, partner, number, true, table.stake, playing, paused);
        if (partner == null) {
            return;
        }
        sendCaroTable(partner, host, number, false, table.stake, playing, paused);
        if (table.stake != null && table.invite == null && !playing && !paused) {
            CaroInvite invite = new CaroInvite(UUID.randomUUID().toString(), host, partner, table.stake,
                now + CARO_INVITE_MILLIS);
            table.invite = invite;
            Object event = Map.of("type", "caro-invite", "id", invite.id(), "from", host.session.getId(),
                "fromName", host.name, "to", partner.session.getId(), "toName", partner.name, "stake", invite.stake());
            sendGame(host, event);
            sendGame(partner, event);
        }
    }

    private void sendCaroTable(Guest viewer, Guest partner, int number, boolean host, Long stake,
                               boolean playing, boolean paused) {
        Map<String, Object> event = new LinkedHashMap<>(Map.of("type", "caro-table", "table", number,
            "host", host, "playing", playing, "paused", paused));
        if (partner != null) {
            event.put("partner", partner.name);
        }
        if (stake != null) {
            event.put("stake", stake);
        }
        sendGame(viewer, event);
    }

    private void replyCaro(Guest guest, CaroTableSession table, JsonNode packet, long now) throws IOException {
        CaroInvite invite = table.invite;
        if (invite == null || invite.to() != guest || !invite.id().equals(packet.path("inviteId").asText())
            || !packet.path("accept").isBoolean()) {
            return;
        }
        if (!packet.path("accept").asBoolean()) {
            leaveSeat(guest);
            return;
        }
        Guest sender = invite.from();
        int number = map.caroTable(guest.seat);
        String xUser = sender.access.userId();
        String oUser = guest.access.userId();
        if (!atCaroTable(sender, number) || table.host != sender || xUser.equals(oUser)
            || reserved(xUser) > 0 || reserved(oUser) > 0
            || invite.stake() > available(sender) || invite.stake() > available(guest)) {
            leaveSeat(guest);
            Object event = Map.of("type", "caro-error", "message", "Không thể mở ván. Hãy kiểm tra số dư CPoint của hai người.");
            sendGame(sender, event);
            sendGame(guest, event);
            return;
        }
        ChatRoomCaro match = new ChatRoomCaro(new ChatRoomCaro.Player(xUser, sender.name),
            new ChatRoomCaro.Player(oUser, guest.name), invite.stake(), now);
        caroMatches.put(xUser, match);
        caroMatches.put(oUser, match);
        endCaroInvite(table);
        table.stake = null;
        publishCaro(match);
    }

    private void expireCaroInvites(long now) throws IOException {
        List<Guest> expired = caroTables.values().stream().map(table -> table.invite)
            .filter(invite -> invite != null && invite.expiresAt() <= now).map(CaroInvite::to).toList();
        for (Guest guest : expired) {
            leaveSeat(guest);
        }
    }

    private void endCaroInvite(CaroTableSession table) {
        CaroInvite invite = table.invite;
        if (invite != null) {
            table.invite = null;
            Object event = Map.of("type", "caro-invite-end", "id", invite.id());
            sendGame(invite.from(), event);
            sendGame(invite.to(), event);
        }
    }

    private void publishCaro(ChatRoomCaro match) {
        Set<Integer> tables = new HashSet<>();
        for (Guest guest : List.copyOf(guests.values())) {
            String userId = guest.access.userId();
            if (guest.roomGames && (userId.equals(match.x.id()) || userId.equals(match.o.id()))) {
                boolean changed = !match.id.equals(guest.activeCaroId) || match.result != null;
                sendCaro(guest, match);
                showWallet(guest);
                int table = map.caroTable(guest.seat);
                if (changed && table != 0) {
                    tables.add(table);
                }
            }
        }
        tables.forEach(table -> refreshCaroTable(table, System.currentTimeMillis()));
    }

    private void sendCaro(Guest guest, ChatRoomCaro match) {
        guest.activeCaroId = match.result == null ? match.id : null;
        Map<String, Object> state = new LinkedHashMap<>(Map.of("type", "caro-state", "id", match.id,
            "xName", match.x.name(), "oName", match.o.name(), "piece", match.piece(guest.access.userId()),
            "stake", match.points, "board", String.valueOf(match.board), "moves", match.moves,
            "expiresAt", match.result == null ? match.deadline() : match.finishedAt, "serverTime", System.currentTimeMillis()));
        if (match.result != null) {
            state.put("result", match.result);
        }
        if (match.lastCell != null) {
            state.put("lastCell", match.lastCell);
        }
        if (match.waitingForReturn()) {
            state.put("waitingForReturn", true);
        }
        if (match.timeout != null) {
            state.put("timedOut", true);
            state.put("timeout", match.timeout);
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
        broadcastPosition(guest, false);
    }

    private void broadcastPosition(Guest guest, boolean includeRoute) throws IOException {
        Map<String, Object> position = new LinkedHashMap<>(Map.of("type", "moved", "id", guest.session.getId(),
            "x", guest.x, "y", guest.y, "facing", guest.facing));
        if (guest.interactions) {
            position.put("seat", guest.seat);
            position.put("approaching", guest.approach != null);
            if (includeRoute && guest.approach != null) {
                position.put("route", guest.approach.route(guest));
            }
        }
        broadcast(position);
    }

    private void seat(Guest guest, JsonNode packet, long now) throws IOException {
        if (!guest.interactions || guest.highFiveUntil > now) {
            return;
        }
        if ("stand".equals(packet.path("action").asText())) {
            leaveSeat(guest);
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
        boolean onlyCaro = packet.path("caro").asBoolean(false);
        List<Integer> chairs = java.util.stream.IntStream.range(0, map.layout().objects().size()).boxed()
            .filter(index -> requested == null || index == requested.intValue())
            .filter(index -> !onlyCaro || map.caroTable(index) != 0)
            .filter(index -> canTakeSeat(guest, index))
            .sorted(Comparator.comparingDouble(index -> ChatRoomMap.distance(origin, seatApproach(index)))).toList();
        if (requested == null && onlyCaro && guest.activeCaroId != null) {
            int partnerTable = guests.values().stream()
                .filter(other -> other != guest && other.roomGames && other.session.isOpen()
                    && guest.activeCaroId.equals(other.activeCaroId))
                .mapToInt(other -> map.caroTable(other.seat)).filter(table -> table != 0).findFirst().orElse(0);
            List<Integer> atPartnerTable = chairs.stream().filter(index -> map.caroTable(index) == partnerTable).toList();
            if (!atPartnerTable.isEmpty()) {
                chairs = atPartnerTable;
            }
        }
        Integer nearest = null;
        double shortest = Double.POSITIVE_INFINITY;
        List<Point> bestPath = List.of();
        for (int chair : chairs) {
            Point destination = seatApproach(chair);
            if (ChatRoomMap.distance(origin, destination) >= shortest) {
                break;
            }
            List<Point> path = map.path(origin, destination);
            if (path.isEmpty()) {
                continue;
            }
            double length = 0;
            Point previous = origin;
            for (Point next : path) {
                length += ChatRoomMap.distance(previous, next);
                previous = next;
            }
            if (length < shortest) {
                nearest = chair;
                shortest = length;
                bestPath = path;
            }
        }
        startApproach(guest, nearest, null, bestPath);
    }

    private boolean canTakeSeat(Guest guest, int index) {
        if (index == map.layout().presentation().chair() && !guest.presentation) {
            return false;
        }
        if (!"chair".equals(map.layout().objects().get(index).kind())) {
            return false;
        }
        boolean caro = map.caroTable(index) != 0;
        ChatRoomMap.BoardTable board = map.boardTable(index);
        if (board != null) {
            if (!guest.boardGames || !guest.roomGames || reserved(guest.access.userId()) > 0
                && !boardParticipant(guest, board.id())) {
                return false;
            }
            BoardSession table = boardSessions.get(board.id());
            if (table != null && table.match != null && table.match.reason == null) {
                int side = table.match.side(guest.access.userId());
                if (side < 0 || board.chairs().get(side) != index) {
                    return false;
                }
            }
        }
        if (caro && !guest.roomGames) {
            return false;
        }
        return guests.values().stream().filter(other -> other != guest).noneMatch(other -> {
            Integer reserved = other.approach == null ? null : other.approach.chair();
            return Integer.valueOf(index).equals(other.seat) || Integer.valueOf(index).equals(reserved)
                || (caro || board != null) && guest.access.userId().equals(other.access.userId())
                    && (map.caroTable(other.seat) != 0 || map.caroTable(reserved) != 0
                        || map.boardTable(other.seat) != null || map.boardTable(reserved) != null);
        });
    }

    private Point seatApproach(int index) {
        ChatRoomMap.Furniture chair = map.layout().objects().get(index);
        double clearance = map.layout().radius() + 8;
        double behind = "south".equals(chair.facing()) ? chair.y() - clearance : chair.y() + chair.h() + clearance;
        return new Point(chair.x() + chair.w() / 2, behind);
    }

    private void takeSeat(Guest guest, int chair, Point destination) throws IOException {
        guest.seat = chair;
        guest.x = destination.x();
        guest.y = destination.y();
        guest.facing = "south".equals(map.layout().objects().get(chair).facing()) ? "south" : "north";
        broadcastPosition(guest);
        int table = map.caroTable(chair);
        if (chair == map.layout().presentation().chair()) {
            stopPresentation();
        }
        if (table != 0) {
            refreshCaroTable(table, System.currentTimeMillis());
        }
        ChatRoomMap.BoardTable board = map.boardTable(chair);
        if (board != null) {
            guest.watchingBoard = board.id();
            boardSessions.computeIfAbsent(board.id(), key -> new BoardSession());
            publishBoard(board.id(), System.currentTimeMillis());
        }
    }

    private void leaveSeat(Guest guest) throws IOException {
        if (guest.seat == null) {
            return;
        }
        boolean presentationSeat = guest.seat == map.layout().presentation().chair();
        int table = map.caroTable(guest.seat);
        ChatRoomMap.BoardTable board = map.boardTable(guest.seat);
        guest.seat = null;
        if (presentationSeat) {
            if (presentationHost() == null) {
                stopPresentation();
            } else {
                broadcastMediaState();
            }
        }
        broadcastPosition(guest);
        if (table != 0) {
            sendGame(guest, Map.of("type", "caro-table"));
            refreshCaroTable(table, System.currentTimeMillis());
        }
        if (board != null) {
            BoardSession session = boardSessions.get(board.id());
            if (session != null) {
                session.ready.remove(guest.access.userId());
                publishBoard(board.id(), System.currentTimeMillis());
            }
        }
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

    private void approachDice(Guest guest) throws IOException {
        if (!guest.roomGames || !guest.interactions || guest.seat != null || map.layout().diceArea() == null) {
            return;
        }
        ChatRoomMap.Area area = map.layout().diceArea();
        Point origin = point(guest);
        List<Point> targets = new ArrayList<>();
        targets.add(new Point(Math.max(area.x() + 24, Math.min(guest.x, area.x() + area.w() - 24)),
            Math.max(area.y() + 24, Math.min(guest.y, area.y() + area.h() - 24))));
        for (double x : new double[] {area.x() + 24, area.x() + area.w() / 2, area.x() + area.w() - 24}) {
            for (double y : new double[] {area.y() + 24, area.y() + area.h() - 24}) {
                targets.add(new Point(x, y));
            }
        }
        targets.sort(Comparator.comparingDouble(target -> ChatRoomMap.distance(origin, target)));
        for (Point target : targets) {
            if (startApproach(guest, null, null, map.path(origin, target))) {
                return;
            }
        }
    }

    private boolean startApproach(Guest guest, Integer chair, Guest partner, List<Point> path) throws IOException {
        if (path.isEmpty()) {
            return false;
        }
        guest.approach = new Approach(chair, partner, new ArrayDeque<>(path), map.layout().speed() * APPROACH_SPEED_MULTIPLIER);
        guest.lastApproachStep = System.nanoTime();
        guest.diceViewing = false;
        broadcastPosition(guest, true);
        return true;
    }

    private void stopApproach(Guest guest) throws IOException {
        if (guest.approach != null) {
            guest.approach = null;
            broadcastPosition(guest);
        }
    }

    @Scheduled(fixedDelay = APPROACH_TICK_MILLIS)
    public synchronized void walkApproaches() throws IOException {
        long now = System.currentTimeMillis();
        tickFloor(now);
        long stepAt = System.nanoTime();
        for (Guest guest : List.copyOf(guests.values())) {
            Approach approach = guest.approach;
            if (approach == null || !guest.session.isOpen()) {
                continue;
            }
            Guest partner = approach.partner();
            if ((partner != null && (!canHighFive(partner, now) || hasInvitation(partner) || hasInvitation(guest)))
                || (approach.chair() != null && !canTakeSeat(guest, approach.chair()))) {
                stopApproach(guest);
                continue;
            }
            double stride = approach.speed() * (stepAt - guest.lastApproachStep) / 1e9;
            guest.lastApproachStep = stepAt;
            advanceApproach(guest, approach.path(), stride);
            if (approach.path().isEmpty()) {
                guest.approach = null;
                if (approach.chair() != null) {
                    takeSeat(guest, approach.chair(), point(guest));
                    continue;
                }
                if (partner != null && inHighFiveRange(guest, partner)) {
                    sendHighFiveInvite(guest, partner, now);
                }
            }
            broadcastPosition(guest);
        }
    }

    private void advanceApproach(Guest guest, Deque<Point> path, double stride) {
        while (!path.isEmpty() && stride > 0) {
            Point next = path.getFirst();
            double distance = ChatRoomMap.distance(point(guest), next);
            if (distance > 0) {
                guest.facing = facingFrom(guest, next.x(), next.y());
            }
            if (distance > stride) {
                guest.x += (next.x() - guest.x) * stride / distance;
                guest.y += (next.y() - guest.y) * stride / distance;
                return;
            }
            guest.x = next.x();
            guest.y = next.y();
            path.removeFirst();
            stride -= distance;
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

    private Guest presentationHost() {
        return guests.values().stream().filter(guest -> guest.presentation && guest.name != null
            && guest.session.isOpen() && Integer.valueOf(map.layout().presentation().chair()).equals(guest.seat))
            .findFirst().orElse(null);
    }

    private void sendMediaState(Guest viewer) {
        Guest host = presentationHost();
        List<Map<String, Object>> ice = new ArrayList<>();
        for (String url : mediaIceUrls.split(",")) {
            String trimmed = url.strip();
            if (trimmed.startsWith("stun:") || trimmed.startsWith("turn:") || trimmed.startsWith("turns:")) {
                Map<String, Object> server = new LinkedHashMap<>();
                server.put("urls", trimmed);
                if (!trimmed.startsWith("stun:") && !mediaTurnUsername.isBlank()) {
                    server.put("username", mediaTurnUsername);
                    server.put("credential", mediaTurnCredential);
                }
                ice.add(server);
            }
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "media-state");
        state.put("id", mediaId);
        state.put("controller", host == null ? "" : host.session.getId());
        state.put("name", host == null ? "" : host.name);
        state.put("source", mediaSource);
        state.put("title", mediaTitle);
        state.put("videoId", mediaVideoId);
        state.put("paused", mediaPaused);
        state.put("position", mediaPosition);
        state.put("updatedAt", mediaUpdatedAt);
        state.put("serverTime", System.currentTimeMillis());
        state.put("iceServers", ice);
        sendGame(viewer, state);
    }

    private void broadcastMediaState() {
        guests.values().stream().filter(guest -> guest.presentation && guest.name != null)
            .forEach(this::sendMediaState);
    }

    private void stopPresentation() {
        mediaSource = "idle";
        mediaTitle = mediaVideoId = "";
        mediaId = UUID.randomUUID().toString();
        mediaPosition = 0;
        mediaPaused = true;
        mediaUpdatedAt = System.currentTimeMillis();
        guests.values().forEach(guest -> guest.mediaWatchId = null);
        broadcastMediaState();
    }

    private boolean validPresentationId(String id) {
        try {
            UUID value = UUID.fromString(id);
            return value.version() == 4 && value.variant() == 2 && value.toString().equalsIgnoreCase(id);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** Chỉ chuyển tín hiệu WebRTC; ảnh, video và âm thanh không đi qua bộ nhớ máy chủ. */
    private void presentation(Guest guest, String type, JsonNode packet, long now) {
        if (!guest.presentation) {
            return;
        }
        Guest host = presentationHost();
        if ("media-signal".equals(type)) {
            Guest target = guests.get(packet.path("to").asText());
            String kind = packet.path("kind").asText();
            String payload = packet.path("payload").asText();
            Guest viewer = guest == host ? target : guest;
            if (host == null || target == null || !target.presentation || viewer == host || viewer == null
                || !target.session.isOpen() || !(guest == host || target == host)
                || !mediaId.equals(packet.path("id").asText()) || "idle".equals(mediaSource)
                || "youtube".equals(mediaSource) || payload.length() > 20000
                || !Set.of("offer", "answer", "ice").contains(kind)
                || "offer".equals(kind) && guest != host || "answer".equals(kind) && target != host
                || viewer.mediaWatchId == null || !viewer.mediaWatchId.equals(packet.path("connection").asText())) {
                return;
            }
            sendGame(target, Map.of("type", "media-signal", "id", mediaId, "from", guest.session.getId(),
                "connection", viewer.mediaWatchId, "kind", kind, "payload", payload));
            return;
        }
        if ("media-watch".equals(type)) {
            String connection = packet.path("connection").asText();
            if (host != null && host != guest && !Set.of("idle", "youtube").contains(mediaSource)
                && mediaId.equals(packet.path("id").asText())
                && validPresentationId(connection) && !connection.equals(guest.mediaWatchId)) {
                guest.mediaWatchId = connection;
                sendGame(host, Map.of("type", "media-peer", "id", mediaId, "peer", guest.session.getId(),
                    "connection", connection));
            }
            return;
        }
        if (guest != host) {
            sendGame(guest, Map.of("type", "media-error", "message", "Ngồi vào bàn trình chiếu để điều khiển."));
            return;
        }
        if ("media-stop".equals(type)) {
            stopPresentation();
            return;
        }
        if ("media-control".equals(type) && now - guest.lastMediaCommand < 100) {
            return;
        }
        if ("media-start".equals(type)) {
            String source = packet.path("source").asText();
            String id = packet.path("id").asText();
            String video = packet.path("videoId").asText();
            if (!Set.of("video", "image", "screen", "youtube").contains(source)
                || !validPresentationId(id)
                || "youtube".equals(source) && !video.matches("[a-zA-Z0-9_-]{11}")) {
                return;
            }
            mediaId = id;
            mediaSource = source;
            mediaVideoId = "youtube".equals(source) ? video : "";
            String title = clean(packet.path("title").asText());
            mediaTitle = title.substring(0, Math.min(100, title.length()));
            mediaPosition = 0;
            mediaPaused = false;
            guests.values().forEach(viewer -> viewer.mediaWatchId = null);
        } else {
            double position = packet.path("position").asDouble(-1);
            if (!"youtube".equals(mediaSource) || !mediaId.equals(packet.path("id").asText())
                || !Double.isFinite(position) || position < 0 || position > 864000
                || !packet.path("paused").isBoolean()) {
                return;
            }
            mediaPosition = position;
            mediaPaused = packet.path("paused").asBoolean();
        }
        guest.lastMediaCommand = mediaUpdatedAt = now;
        broadcastMediaState();
    }

    private void reaction(Guest guest, JsonNode packet, long now) throws IOException {
        String reaction = packet.path("reaction").asText();
        if (!guest.reactions || !REACTIONS.contains(reaction) || now - guest.lastReaction < 1200) {
            return;
        }
        guest.lastReaction = now;
        broadcast(Map.of("type", "reaction", "playerId", guest.session.getId(), "reaction", reaction),
            viewer -> viewer.reactions);
    }

    private void tickFloor(long now) throws IOException {
        if (map.layout().colorTiles().isEmpty()) {
            return;
        }
        List<RoomColorFloor.Player> present = guests.values().stream()
            .filter(guest -> guest.name != null && guest.session.isOpen() && guest.floorGame)
            .map(guest -> new RoomColorFloor.Player(guest.access.userId(), guest.name, guest.x, guest.y)).toList();
        if (colorFloor.tick(now, present, map.layout().colorTiles())) {
            publishFloor(now);
        }
    }

    private void publishFloor(long now) {
        for (Guest viewer : guests.values()) {
            if (viewer.floorGame && viewer.name != null) {
                sendGame(viewer, colorFloor.snapshot(viewer.access.userId(), now));
            }
        }
    }

    private boolean boardParticipant(Guest guest, String table) {
        BoardSession session = boardSessions.get(table);
        return session != null && session.match != null && session.match.reason == null
            && session.match.side(guest.access.userId()) >= 0;
    }

    private List<Guest> boardOccupants(ChatRoomMap.BoardTable table) {
        List<Guest> occupants = new ArrayList<>();
        for (int chair : table.chairs()) {
            occupants.add(guests.values().stream().filter(guest -> guest.name != null && guest.session.isOpen()
                && Integer.valueOf(chair).equals(guest.seat)).findFirst().orElse(null));
        }
        return occupants;
    }

    private void board(Guest guest, String type, JsonNode packet, long now) {
        if (!guest.boardGames) {
            return;
        }
        String tableId = packet.path("table").asText();
        ChatRoomMap.BoardTable layout = map.layout().boardTables().stream()
            .filter(table -> table.id().equals(tableId)).findFirst().orElse(null);
        if (layout == null) {
            return;
        }
        BoardSession session = boardSessions.computeIfAbsent(tableId, key -> new BoardSession());
        if ("board-watch".equals(type)) {
            guest.watchingBoard = tableId;
            sendBoard(guest, tableId, session, now);
            return;
        }
        if (!guest.roomGames || !layout.chairs().contains(guest.seat)) {
            boardError(guest, "Hãy ngồi vào ghế của bàn để chơi.");
            return;
        }
        if (session.match != null && session.match.expire(now)) {
            publishBoard(tableId, now);
            return;
        }
        if ("board-ready".equals(type)) {
            if (session.match != null && (!session.match.settled || session.match.reason == null)) {
                return;
            }
            long points = integer(packet, "points");
            if (points < ChatRoomGames.MIN_CARO_STAKE || points > ChatRoomGames.MAX_STAKE
                || reserved(guest.access.userId()) > 0 || available(guest) < points) {
                boardError(guest, "Mỗi bạn cần góp ít nhất 10 CPoint và đủ số dư.");
                return;
            }
            if (session.ready.isEmpty()) {
                session.points = points;
            } else if (session.points != points) {
                boardError(guest, "Hai bạn cần chọn cùng số CPoint.");
                return;
            }
            session.match = null;
            session.ready.add(guest.access.userId());
            List<Guest> occupants = boardOccupants(layout);
            if (occupants.stream().allMatch(player -> player != null && session.ready.contains(player.access.userId())
                && available(player) >= points && reserved(player.access.userId()) == 0)
                && !occupants.get(0).access.userId().equals(occupants.get(1).access.userId())) {
                Guest first = occupants.get(0);
                Guest second = occupants.get(1);
                session.match = new RoomBoardMatch("xiangqi".equals(layout.kind()),
                    new RoomBoardMatch.Player(first.access.userId(), first.name),
                    new RoomBoardMatch.Player(second.access.userId(), second.name), points,
                    map.layout().boardClock().baseMillis(), map.layout().boardClock().incrementMillis(), now);
                session.ready.clear();
                guests.values().stream().filter(player -> player.roomGames).forEach(this::showWallet);
            }
            publishBoard(tableId, now);
            return;
        }
        RoomBoardMatch match = session.match;
        if (match == null) {
            return;
        }
        int version = packet.path("version").isIntegralNumber() && packet.path("version").canConvertToInt()
            ? packet.path("version").intValue() : -1;
        boolean changed;
        if ("board-move".equals(type)) {
            changed = packet.path("from").isIntegralNumber() && packet.path("from").canConvertToInt()
                && packet.path("to").isIntegralNumber() && packet.path("to").canConvertToInt()
                && match.move(guest.access.userId(), packet.path("matchId").asText(), version,
                    packet.path("from").asInt(-1), packet.path("to").asInt(-1),
                    packet.path("promotion").asText(""), now);
        } else {
            for (String cell : List.of("from", "to")) {
                if (packet.has(cell) && (!packet.path(cell).isIntegralNumber()
                    || !packet.path(cell).canConvertToInt())) {
                    boardError(guest, "Ô cờ chưa phù hợp.");
                    return;
                }
            }
            changed = match.action(guest.access.userId(), packet.path("matchId").asText(), version,
                packet.path("action").asText(), packet.has("from") ? packet.path("from").asInt(-1) : null,
                packet.has("to") ? packet.path("to").asInt(-1) : null, packet.path("promotion").asText(""), now);
        }
        if (!changed) {
            boardError(guest, "Nước đi hoặc yêu cầu chưa phù hợp với ván hiện tại.");
            sendBoard(guest, tableId, session, now);
            return;
        }
        publishBoard(tableId, now);
    }

    private void boardError(Guest guest, String message) {
        sendGame(guest, Map.of("type", "board-error", "message", message));
    }

    private void publishBoard(String table, long now) {
        BoardSession session = boardSessions.get(table);
        for (Guest viewer : guests.values()) {
            if (viewer.boardGames && viewer.name != null) {
                sendBoardTables(viewer);
                if (table.equals(viewer.watchingBoard)) {
                    sendBoard(viewer, table, session, now);
                }
            }
        }
    }

    private void sendBoardTables(Guest viewer) {
        List<Map<String, Object>> states = new ArrayList<>();
        for (ChatRoomMap.BoardTable table : map.layout().boardTables()) {
            BoardSession session = boardSessions.get(table.id());
            List<String> names = boardOccupants(table).stream().map(guest -> guest == null ? "" : guest.name).toList();
            String status = session != null && session.match != null && session.match.reason == null ? "playing"
                : names.stream().allMatch(String::isEmpty) ? "empty" : "waiting";
            states.add(Map.of("id", table.id(), "status", status, "players", names));
        }
        sendGame(viewer, Map.of("type", "board-tables", "tables", states));
    }

    private void sendBoard(Guest viewer, String tableId, BoardSession session, long now) {
        ChatRoomMap.BoardTable table = map.layout().boardTables().stream()
            .filter(item -> item.id().equals(tableId)).findFirst().orElseThrow();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "board-state");
        state.put("table", tableId);
        state.put("kind", table.kind());
        state.put("name", table.name());
        state.put("points", session.points);
        state.put("seated", table.chairs().contains(viewer.seat));
        state.put("ready", session.ready.contains(viewer.access.userId()));
        state.put("readyNames", boardOccupants(table).stream().filter(player -> player != null
            && session.ready.contains(player.access.userId())).map(player -> player.name).toList());
        state.put("players", boardOccupants(table).stream().map(player -> player == null ? "" : player.name).toList());
        state.put("baseMillis", map.layout().boardClock().baseMillis());
        state.put("incrementMillis", map.layout().boardClock().incrementMillis());
        if (session.match != null) {
            state.put("match", session.match.snapshot(viewer.access.userId(), now));
        }
        sendGame(viewer, state);
    }

    private void chat(Guest guest, JsonNode packet, long now) throws IOException {
        // Giữ ZWJ để emoji ghép vẫn là một grapheme, lọc ký tự điều khiển còn lại.
        String text = Normalizer.normalize(packet.path("text").asText(), Normalizer.Form.NFC)
            .replaceAll("[\\p{Cc}\\p{Cf}&&[^\\x{200D}]]", " ").strip();
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
        if (chatHistory.size() == ChatRoomLimits.CHAT_HISTORY_LENGTH) {
            chatHistory.removeFirst();
        }
        chatHistory.addLast(event);
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
            if (Integer.valueOf(map.layout().presentation().chair()).equals(guest.seat)) {
                if (presentationHost() == null) {
                    stopPresentation();
                } else {
                    broadcastMediaState();
                }
            }
            colorFloor.leave(guest.access.userId());
            ChatRoomMap.BoardTable board = map.boardTable(guest.seat);
            if (board != null) {
                BoardSession boardSession = boardSessions.get(board.id());
                if (boardSession != null) {
                    boardSession.ready.remove(guest.access.userId());
                    publishBoard(board.id(), System.currentTimeMillis());
                }
            }
            String userId = guest.access.userId();
            ChatRoomCaro match = caroMatches.get(userId);
            long now = System.currentTimeMillis();
            if (guest.roomGames && match != null && guests.values().stream().noneMatch(other ->
                other.roomGames && other.session.isOpen() && other.access.userId().equals(userId))
                && (match.expire(now) || match.presence(userId, false))) {
                publishCaro(match);
            }
            int table = map.caroTable(guest.seat);
            if (table != 0) {
                refreshCaroTable(table, System.currentTimeMillis());
            }
            invitations.values().removeIf(invite -> invite.from() == guest || invite.to() == guest);
            broadcast(Map.of("type", "left", "id", session.getId(), "name", guest.name));
            if (guests.values().stream().noneMatch(other -> other.name != null && other.session.isOpen())) {
                chatHistory.clear();
            }
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
