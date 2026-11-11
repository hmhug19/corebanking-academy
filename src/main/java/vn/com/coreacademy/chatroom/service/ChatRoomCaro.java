package vn.com.coreacademy.chatroom.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** One match, owned by the room monitor. Only wallet transfers outlive a room session. */
final class ChatRoomCaro {
    static final int BOARD_SIZE = 50;
    static final int MAX_CELLS = BOARD_SIZE * BOARD_SIZE;
    static final long TURN_MILLIS = 120_000;
    private static final int[][] LINES = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

    record Player(String id, String name) {}

    final UUID id = UUID.randomUUID();
    final UUID walletTransfer = UUID.randomUUID();
    final Player x;
    final Player o;
    final long points;
    final char[] board = new char[MAX_CELLS];
    private final Set<String> away = new LinkedHashSet<>();
    private long turnUntil;
    int moves;
    Integer lastCell;
    String result;
    String timeout;
    long finishedAt;
    boolean settled;

    ChatRoomCaro(Player x, Player o, long points, long now) {
        this.x = x;
        this.o = o;
        this.points = points;
        this.turnUntil = now + TURN_MILLIS;
        Arrays.fill(board, '.');
    }

    String piece(String player) {
        return x.id().equals(player) ? "X" : "O";
    }

    Player winner() {
        return "X".equals(result) ? x : o;
    }

    Player loser() {
        return "X".equals(result) ? o : x;
    }

    boolean waitingForReturn() {
        return !away.isEmpty();
    }

    long deadline() {
        return turnUntil;
    }

    boolean presence(String player, boolean present) {
        if (result != null) {
            return false;
        }
        return present ? away.remove(player) : away.add(player);
    }

    boolean expire(long now) {
        if (result != null || now < deadline()) {
            return false;
        }
        if (waitingForReturn()) {
            String missing = away.iterator().next();
            finish(x.id().equals(missing) ? "O" : "X", "away", now);
        } else {
            finish(moves % 2 == 0 ? "O" : "X", "turn", now);
        }
        return true;
    }

    boolean play(String player, String gameId, long move, long cell, long now) {
        if (result != null || waitingForReturn() || now >= turnUntil || !id.toString().equals(gameId)
            || moves != move || cell < 0 || cell >= MAX_CELLS || board[(int) cell] != '.') {
            return false;
        }
        char piece = moves % 2 == 0 ? 'X' : 'O';
        if (piece != piece(player).charAt(0)) {
            return false;
        }
        lastCell = (int) cell;
        board[lastCell] = piece;
        moves++;
        turnUntil = now + TURN_MILLIS;
        if (wins(board, lastCell, piece)) {
            finish(String.valueOf(piece), null, now);
        } else if (moves == MAX_CELLS) {
            finish("draw", null, now);
        }
        return true;
    }

    private void finish(String outcome, String cause, long now) {
        result = outcome;
        timeout = cause;
        finishedAt = now;
        away.clear();
        settled = "draw".equals(outcome);
    }

    static boolean wins(char[] board, int cell, char piece) {
        int x = cell % BOARD_SIZE;
        int y = cell / BOARD_SIZE;
        for (int[] line : LINES) {
            int dx = line[0];
            int dy = line[1];
            int before = 1;
            while (pieceAt(board, x - dx * before, y - dy * before) == piece) {
                before++;
            }
            int after = 1;
            while (pieceAt(board, x + dx * after, y + dy * after) == piece) {
                after++;
            }
            int length = before + after - 1;
            if (length > 5 || length == 5 && (pieceAt(board, x - dx * before, y - dy * before) == '.'
                || pieceAt(board, x + dx * after, y + dy * after) == '.')) {
                return true;
            }
        }
        return false;
    }

    private static char pieceAt(char[] board, int x, int y) {
        return x >= 0 && x < BOARD_SIZE && y >= 0 && y < BOARD_SIZE ? board[y * BOARD_SIZE + x] : '#';
    }
}
