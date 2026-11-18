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
            "interactions", true, "reactions", capabilities, "boardGames", capabilities, "floorGame", capabilities,
            "presentation", capabilities));
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
    void diceSelectionRoutesToAWalkablePointInsideTheDiceArea() throws Exception {
        var a = join("a", "a", true);
        command(a, Map.of("type", "dice-approach"));
        var points = a.latest("moved").path("route").path("points");
        assertTrue(points.size() >= 2);
        var end = points.get(points.size() - 1);
        assertTrue(map.layout().diceArea().contains(end.path("x").asDouble(), end.path("y").asDouble()));
        assertTrue(map.walkable(end.path("x").asDouble(), end.path("y").asDouble()));
        verify(games, never()).settleCaro(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void onlyPresentationChairCanStartAndLeavingStopsTheSessionWithoutMediaStorage() throws Exception {
        var host = join("host", "host", true);
        var viewer = join("viewer", "viewer", true);
        String id = java.util.UUID.randomUUID().toString();
        command(viewer, Map.of("type", "media-start", "id", id, "source", "youtube", "videoId", "dQw4w9WgXcQ"));
        assertEquals("idle", viewer.latest("media-state").path("source").asText());
        assertNotNull(viewer.latest("media-error"));
        arrangeSeat(host, map.layout().presentation().chair());
        command(host, Map.of("type", "media-start", "id", id, "source", "youtube", "videoId", "dQw4w9WgXcQ"));
        assertEquals(id, viewer.latest("media-state").path("id").asText());
        assertEquals("host", viewer.latest("media-state").path("controller").asText());
        command(viewer, Map.of("type", "media-stop"));
        assertEquals("youtube", viewer.latest("media-state").path("source").asText());
        command(host, Map.of("type", "seat", "action", "stand"));
        assertEquals("idle", viewer.latest("media-state").path("source").asText());
        assertEquals("", viewer.latest("media-state").path("controller").asText());
        verify(games, never()).settleCaro(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void mediaSignalsAreBoundToHostViewerAndConnectionAndRetiredOnDisconnect() throws Exception {
        var host = join("host", "host", true);
        var viewer = join("viewer", "viewer", true);
        var other = join("other", "other", true);
        var old = join("old", "old", false);
        arrangeSeat(host, map.layout().presentation().chair());
        String id = java.util.UUID.randomUUID().toString();
        String connection = java.util.UUID.randomUUID().toString();
        command(host, Map.of("type", "media-start", "id", id, "source", "image"));
        command(viewer, Map.of("type", "media-watch", "id", id, "connection", connection));
        assertEquals("viewer", host.latest("media-peer").path("peer").asText());
        var offer = Map.of("type", "media-signal", "id", id, "connection", connection,
            "to", "viewer", "kind", "offer", "payload", "v=0");
        command(other, offer);
        assertFalse(viewer.events().stream().anyMatch(event -> "media-signal".equals(event.path("type").asText())));
        command(host, offer);
        assertEquals("host", viewer.latest("media-signal").path("from").asText());
        assertFalse(old.events().stream().anyMatch(event -> event.path("type").asText().startsWith("media-")));
        room.disconnect(host.socket());
        assertEquals("idle", viewer.latest("media-state").path("source").asText());
        assertNotEquals(id, viewer.latest("media-state").path("id").asText());
    }

    @Test
    void delayedDisconnectCannotStopANewPresenterAndMalformedIdsAreIgnored() throws Exception {
        var oldHost = join("old-host", "old-host", true);
        var host = join("host", "host", true);
        var viewer = join("viewer", "viewer", true);
        int chair = map.layout().presentation().chair();
        arrangeSeat(oldHost, chair);
        when(oldHost.socket().isOpen()).thenReturn(false);
        arrangeSeat(host, chair);
        String id = java.util.UUID.randomUUID().toString();
        command(host, Map.of("type", "media-start", "id", id, "source", "image"));
        room.disconnect(oldHost.socket());
        assertEquals(id, viewer.latest("media-state").path("id").asText());
        assertEquals("host", viewer.latest("media-state").path("controller").asText());
        command(host, Map.of("type", "media-start", "id", "not-a-uuid", "source", "image"));
        command(viewer, Map.of("type", "media-watch", "id", id, "connection", "not-a-uuid"));
        assertEquals(id, viewer.latest("media-state").path("id").asText());
        assertFalse(host.events().stream().anyMatch(event -> "media-peer".equals(event.path("type").asText())));
    }

    @Test
    void retiredColorFloorIsNotAdvertisedOrStarted() throws Exception {
        var a = join("a", "a", true);
        assertFalse(a.latest("playground-ready").path("floorGame").asBoolean());
        assertNull(map.layout().colorFloor());
        assertTrue(map.layout().colorTiles().isEmpty());
        command(a, Map.of("type", "floor-join"));
        assertFalse(a.events().stream().anyMatch(event -> "floor-state".equals(event.path("type").asText())));
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
