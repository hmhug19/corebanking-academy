package vn.com.coreacademy.chatroom.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoomColorFloorTest {
    private final List<ChatRoomMap.ColorTile> tiles = List.of(
        new ChatRoomMap.ColorTile("mint", "Xanh", "★", "#6eaa99", new ChatRoomMap.Area(0, 0, 100, 100)));

    @Test
    void onePlayerWaitsTwoPlayersStartAndScoreExactlyOnceAtDeadline() {
        var floor = new RoomColorFloor();
        floor.join("a", true);
        assertFalse(floor.tick(1000, List.of(), tiles));
        floor.join("b", true);
        assertTrue(floor.tick(2000, List.of(), tiles));
        var players = List.of(new RoomColorFloor.Player("a", "An", 50, 50),
            new RoomColorFloor.Player("b", "Bình", 100, 50));
        assertTrue(floor.tick(7000, players, tiles));
        assertFalse(floor.tick(11999, players, tiles));
        assertTrue(floor.tick(12000, players, tiles));
        assertEquals(List.of("An"), floor.snapshot("a", 12000).get("winners"));
        assertFalse(floor.tick(12000, players, tiles));
    }

    @Test
    void allFailMeansNoWinnerAndLateArrivalWaitsForNextGame() {
        var floor = new RoomColorFloor();
        floor.join("a", true);
        floor.join("b", true);
        var players = List.of(new RoomColorFloor.Player("a", "An", -1, 50),
            new RoomColorFloor.Player("b", "Bình", 100, 50));
        floor.tick(1000, players, tiles);
        floor.tick(6000, players, tiles);
        floor.join("viewer", true);
        floor.tick(11000, players, tiles);
        assertEquals(List.of(), floor.snapshot("a", 11000).get("winners"));
        assertTrue((Boolean) floor.snapshot("viewer", 11000).get("joined"));
    }

    @Test
    void fiveSafeRoundsAllowSharedWinnersAndEdgesAreHalfOpen() {
        assertTrue(RoomColorFloor.contains(tiles.get(0).area(), 0, 0));
        assertFalse(RoomColorFloor.contains(tiles.get(0).area(), 100, 99));
        var floor = new RoomColorFloor();
        floor.join("a", true);
        floor.join("b", true);
        var players = List.of(new RoomColorFloor.Player("a", "An", 50, 50),
            new RoomColorFloor.Player("b", "Bình", 90, 50));
        floor.tick(1000, players, tiles);
        floor.tick(6000, players, tiles);
        for (int i = 1; i <= 5; i++) {
            assertTrue(floor.tick(6000 + i * 5000, players, tiles));
        }
        assertEquals(List.of("An", "Bình"), floor.snapshot("a", 31000).get("winners"));
    }
}
