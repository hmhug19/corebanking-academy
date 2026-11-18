package vn.com.coreacademy.chatroom.service;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomBoardPositionTest {
    static RoomBoardPosition fen(boolean xiangqi, String diagram, int turn) {
        RoomBoardPosition board = new RoomBoardPosition(xiangqi);
        Arrays.fill(board.cells, '.');
        int index = 0;
        for (char piece : diagram.toCharArray()) {
            if (piece == '/') {
                continue;
            }
            if (Character.isDigit(piece)) {
                index += piece - '0';
            } else {
                board.cells[index] = piece;
                board.identities[index] = index;
                index++;
            }
        }
        board.turn = turn;
        board.castling = 0;
        return board;
    }

    static boolean legal(RoomBoardPosition board, int from, int to, String promotion) {
        return board.legalMoves().contains(new RoomBoardPosition.Move(from, to, promotion));
    }

    private long perft(RoomBoardPosition board, int depth) {
        if (depth == 0) {
            return 1;
        }
        long result = 0;
        for (var move : board.legalMoves()) {
            var next = new RoomBoardPosition(board);
            next.apply(move);
            result += perft(next, depth - 1);
        }
        return result;
    }

    @Test
    void xiangqiCannonIsAFakeRootIfTheCapturingRookWasItsOnlyScreen() {
        // WXF 19.14/19.15 require an actual legal recapture after the hypothetical capture.
        var board = fen(true, "c3k4/9/R8/9/h8/9/4P4/9/9/4K4", 0);
        var capture = new RoomBoardPosition.Move(18, 36, "");
        assertTrue(board.legalMoves().contains(capture));
        assertFalse(board.protectedCapture(capture));
        board.cells[9] = 'p';
        assertTrue(board.protectedCapture(capture));
    }

    @Test
    void chessInitialPerftThroughThreePlies() {
        var board = new RoomBoardPosition(false);
        assertEquals(20, perft(board, 1));
        assertEquals(400, perft(board, 2));
        assertEquals(8902, perft(board, 3));
    }

    @Test
    void chessCastlingRequiresRightsEmptyRouteAndSafeTransit() {
        var board = fen(false, "r3k2r/8/8/8/8/8/8/R3K2R", 0);
        board.castling = 15;
        assertTrue(legal(board, 60, 62, ""));
        assertTrue(legal(board, 60, 58, ""));
        board.cells[5] = 'r';
        assertFalse(legal(board, 60, 62, ""));
        board.cells[5] = '.';
        board.castling = 2;
        assertFalse(legal(board, 60, 62, ""));
        board.cells[57] = 'N';
        assertFalse(legal(board, 60, 58, ""));
    }

    @Test
    void enPassantCannotExposeKingAndOnlyLegalEpChangesRepetitionKey() {
        var board = fen(false, "k7/8/8/r4pPK/8/8/8/8", 0);
        board.enPassant = 21;
        assertFalse(legal(board, 30, 21, ""));
        String withoutLegalEp = board.key();
        board.enPassant = -1;
        assertEquals(withoutLegalEp, board.key());
        board.cells[24] = '.';
        board.enPassant = 21;
        assertTrue(legal(board, 30, 21, ""));
        String withLegalEp = board.key();
        board.enPassant = -1;
        assertNotEquals(withLegalEp, board.key());
    }

    @Test
    void promotionOffersFourChoicesAndCannotOmitChoice() {
        var board = fen(false, "7k/P7/8/8/8/8/8/7K", 0);
        assertFalse(legal(board, 8, 0, ""));
        for (String choice : List.of("q", "r", "b", "n")) {
            assertTrue(legal(board, 8, 0, choice));
            var next = new RoomBoardPosition(board);
            next.apply(new RoomBoardPosition.Move(8, 0, choice));
            assertEquals(Character.toUpperCase(choice.charAt(0)), next.cells[0]);
        }
    }

    @Test
    void matePossibilityDiffersFromAbilityToForceMate() {
        var loneKnight = fen(false, "7k/8/8/8/8/8/8/5N1K", 0);
        assertFalse(loneKnight.canMate(0));
        loneKnight.cells[61] = 'N';
        loneKnight.cells[57] = 'N';
        assertTrue(loneKnight.canMate(0), "Two knights can reach a mate with cooperation");
        var sameBishops = fen(false, "7k/8/8/8/8/8/8/2B1B2K", 0);
        assertFalse(sameBishops.canMate(0));
    }

    @Test
    void xiangqiInitialLegalMovesAndHorseLeg() {
        var board = new RoomBoardPosition(true);
        assertEquals(44, board.legalMoves().size());
        board.cells[73] = 'P';
        assertFalse(legal(board, 82, 63, ""));
    }

    @Test
    void xiangqiElephantEyeRiverCannonScreenAndFlyingKings() {
        var board = fen(true, "4k4/9/9/9/9/4P4/9/9/9/2E1K4", 0);
        assertTrue(legal(board, 83, 63, ""));
        board.cells[73] = 'P';
        assertFalse(legal(board, 83, 63, ""));
        board = fen(true, "4k4/9/9/9/2E1P4/9/9/9/9/4K4", 0);
        assertFalse(legal(board, 38, 22, ""));
        board = fen(true, "4k4/9/9/9/9/r3P4/9/P8/9/C3K4", 0);
        assertTrue(legal(board, 81, 45, ""));
        board.cells[63] = '.';
        assertFalse(legal(board, 81, 45, ""));
        board = fen(true, "4k4/9/9/9/9/4R4/9/9/9/4K4", 0);
        assertFalse(legal(board, 49, 48, ""));
        assertTrue(board.legalMoves().stream().anyMatch(move -> move.from() == 49));
    }

    @Test
    void mapHasExactlySixTablesWithReachableSeats() throws Exception {
        var map = new ChatRoomMap(new com.fasterxml.jackson.databind.ObjectMapper());
        assertEquals(6, map.layout().boardTables().size());
        var mediaChair = map.layout().objects().get(map.layout().presentation().chair());
        assertFalse(map.path(map.layout().spawn(), new ChatRoomMap.Point(mediaChair.x() + mediaChair.w() / 2,
            mediaChair.y() + mediaChair.h() + 20)).isEmpty());
        assertEquals(3, map.layout().boardTables().stream().filter(table -> table.kind().equals("chess")).count());
        for (var table : map.layout().boardTables()) {
            assertEquals(2, table.chairs().size());
            for (int chairIndex : table.chairs()) {
                var chair = map.layout().objects().get(chairIndex);
                var approach = new ChatRoomMap.Point(chair.x() + chair.w() / 2,
                    "south".equals(chair.facing()) ? chair.y() - 20 : chair.y() + chair.h() + 20);
                assertFalse(map.path(map.layout().spawn(), approach).isEmpty(), table.id());
            }
        }
    }
}
