package vn.com.coreacademy.chatroom.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import vn.com.coreacademy.chatroom.config.ChatRoomProperties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatRoomPlaygroundTest {
    private final ObjectMapper json = new ObjectMapper();
    private ChatRoomMap map;
    private ChatRoomGames games;
    private ChatRoomService room;

    private record Client(WebSocketSession socket, List<JsonNode> events) {
        JsonNode latest(String type) {
            return events.stream().filter(event -> type.equals(event.path("type").asText()))
                .reduce((a, b) -> b).orElseThrow();
        }
    }

    @BeforeEach
    void setup() throws Exception {
        map = new ChatRoomMap(json);
        games = mock(ChatRoomGames.class);
        when(games.claim(anyString())).thenReturn(100L);
        when(games.balances(anySet())).thenAnswer(call -> {
            Set<String> users = call.getArgument(0);
            return users.stream().collect(java.util.stream.Collectors.toMap(user -> user, user -> 100L));
        });
        room = new ChatRoomService(json, map, mock(ChatRoomTickets.class),
            new ChatRoomProperties(Duration.ofSeconds(20), Duration.ofSeconds(60),
                Duration.ofSeconds(10), Duration.ofSeconds(2)), games);
    }

    private Client join(String session, String user, boolean capabilities) throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(session);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(Map.of(ChatRoomTickets.ACCESS_ATTRIBUTE,
            new ChatRoomTickets.Access(user, 1, session, Instant.now().plusSeconds(3600))));
        List<JsonNode> events = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            TextMessage message = call.getArgument(0);
            events.add(json.readTree(message.getPayload()));
            return null;
        }).when(socket).sendMessage(any());
        room.connect(socket);
        Client client = new Client(socket, events);
        command(client, Map.of("type", "join", "name", user, "color", "teal", "roomGames", true,
            "interactions", true, "reactions", capabilities, "boardGames", capabilities, "floorGame", capabilities));
        return client;
    }

    private void command(Client client, Object command) throws Exception {
        room.receive(client.socket(), json.writeValueAsString(command));
    }

    private void arrangeSeat(Client client, int chair) throws Exception {
        var guestsField = ChatRoomService.class.getDeclaredField("guests");
        guestsField.setAccessible(true);
        Map<?, ?> guests = (Map<?, ?>) guestsField.get(room);
        Object guest = guests.get(client.socket().getId());
        var seatField = guest.getClass().getDeclaredField("seat");
        seatField.setAccessible(true);
        seatField.set(guest, chair);
    }

    private void ready(Client client, String table) throws Exception {
        command(client, Map.of("type", "board-ready", "table", table, "points", 10));
    }

    @Test
    void sixTablesStartIndependentlyAndBothPlayersMustBeReady() throws Exception {
        List<String> matches = new ArrayList<>();
        for (var table : map.layout().boardTables()) {
            var a = join(table.id() + "-a", table.id() + "-a", true);
            var b = join(table.id() + "-b", table.id() + "-b", true);
            arrangeSeat(a, table.chairs().get(0));
            arrangeSeat(b, table.chairs().get(1));
            command(a, Map.of("type", "board-watch", "table", table.id()));
            ready(a, table.id());
            assertFalse(a.latest("board-state").has("match"));
            ready(b, table.id());
            command(b, Map.of("type", "board-watch", "table", table.id()));
            var state = b.latest("board-state");
            assertEquals(0, state.path("match").path("version").asInt());
            assertEquals(1, state.path("match").path("side").asInt());
            matches.add(state.path("match").path("id").asText());
        }
        assertEquals(6, Set.copyOf(matches).size());
    }

    @Test
    void spectatorsCannotMoveAndConcurrentDuplicateMovesCountOnceAndReconnectKeepsMatch() throws Exception {
        var a = join("a", "a", true);
        var b = join("b", "b", true);
        var viewer = join("viewer", "viewer", true);
        var table = map.layout().boardTables().get(0);
        arrangeSeat(a, table.chairs().get(0));
        arrangeSeat(b, table.chairs().get(1));
        command(a, Map.of("type", "board-watch", "table", table.id()));
        ready(a, table.id());
        ready(b, table.id());
        String match = a.latest("board-state").path("match").path("id").asText();
        Map<String, Object> move = Map.of("type", "board-move", "table", table.id(), "matchId", match,
            "version", 0, "from", 52, "to", 36);
        command(viewer, move);
        assertTrue(viewer.latest("board-error").path("message").asText().contains("ghế"));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of(pool.submit(() -> { start.await(); command(a, move); return true; }),
                pool.submit(() -> { start.await(); command(a, move); return true; }));
            start.countDown();
            for (var future : futures) {
                assertTrue(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, a.latest("board-state").path("match").path("version").asInt());
        room.disconnect(a.socket());
        var reconnected = join("new-a", "a", true);
        var snapshot = reconnected.latest("board-state").path("match");
        assertEquals(match, snapshot.path("id").asText());
        assertEquals(1, snapshot.path("version").asInt());
        assertEquals(0, snapshot.path("side").asInt());
        verify(games, never()).settleCaro(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void movementAfterFloorDeadlineCannotRetroactivelySaveAPlayer() throws Exception {
        var a = join("a", "a", true);
        var b = join("b", "b", true);
        var field = ChatRoomService.class.getDeclaredField("colorFloor");
        field.setAccessible(true);
        var floor = (RoomColorFloor) field.get(room);
        floor.join("a", true);
        floor.join("b", true);
        long now = System.currentTimeMillis();
        var present = List.of(new RoomColorFloor.Player("a", "a", 0, 0),
            new RoomColorFloor.Player("b", "b", 0, 0));
        floor.tick(now - 11000, present, map.layout().colorTiles());
        floor.tick(now - 6000, present, map.layout().colorTiles());
        String target = (String) floor.snapshot("a", now).get("target");
        var safe = map.layout().colorTiles().stream().filter(tile -> tile.color().equals(target)).findFirst().orElseThrow();
        var wrong = map.layout().colorTiles().stream().filter(tile -> !tile.color().equals(target)).findFirst().orElseThrow();
        var guestsField = ChatRoomService.class.getDeclaredField("guests");
        guestsField.setAccessible(true);
        Map<?, ?> guests = (Map<?, ?>) guestsField.get(room);
        for (var client : List.of(a, b)) {
            Object guest = guests.get(client.socket().getId());
            var area = client == a ? wrong.area() : safe.area();
            for (String coordinate : List.of("x", "y")) {
                var position = guest.getClass().getDeclaredField(coordinate);
                position.setAccessible(true);
                position.setDouble(guest, (coordinate.equals("x") ? area.x() : area.y()) + 60);
            }
        }
        command(a, Map.of("type", "move", "x", safe.area().x() + 60, "y", safe.area().y() + 60));
        assertTrue(a.latest("floor-state").path("eliminated").asBoolean());
        assertEquals("b", a.latest("floor-state").path("winners").get(0).asText());
        String result = a.latest("floor-state").toString();
        command(a, Map.of("type", "ping"));
        assertEquals(result, a.latest("floor-state").toString());
    }

    @Test
    void chatPreservesJoinedEmojiAndSanitizesControlCharacters() throws Exception {
        var a = join("a", "a", true);
        String text = "Xin chào 👨‍👩‍👧‍👦 🇻🇳";
        command(a, Map.of("type", "chat", "text", text + "\u0001\u202e"));
        assertEquals(text, a.latest("chat").path("text").asText());
        var late = join("late", "late", true);
        assertEquals(text, late.latest("welcome").path("history").get(0).path("text").asText());
    }

    @Test
    void reactionsAreRateLimitedAndOnlySentToCapableClientsAndNeverEnterChatHistory() throws Exception {
        var a = join("a", "a", true);
        var old = join("old", "old", false);
        command(a, Map.of("type", "reaction", "reaction", "unknown"));
        command(a, Map.of("type", "reaction", "reaction", "love"));
        command(a, Map.of("type", "reaction", "reaction", "clap"));
        assertEquals(1, a.events().stream().filter(event -> "reaction".equals(event.path("type").asText())).count());
        assertEquals("a", a.latest("reaction").path("playerId").asText());
        assertFalse(old.events().stream().anyMatch(event -> "reaction".equals(event.path("type").asText())));
        var late = join("late", "late", true);
        assertEquals(0, late.latest("welcome").path("history").size());
        assertFalse(old.events().stream().anyMatch(event -> "board-tables".equals(event.path("type").asText())));
    }
}
