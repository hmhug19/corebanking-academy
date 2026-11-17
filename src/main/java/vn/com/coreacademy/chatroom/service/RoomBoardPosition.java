package vn.com.coreacademy.chatroom.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Luật di chuyển thuần, không truy cập I/O. Hàng 0 nằm ở phía đen. */
final class RoomBoardPosition {
    record Move(int from, int to, String promotion) {}

    final boolean xiangqi;
    final int width;
    final int height;
    final char[] cells;
    final int[] identities;
    int turn;
    int castling = 15;
    int enPassant = -1;
    int quiet;

    RoomBoardPosition(boolean xiangqi) {
        this.xiangqi = xiangqi;
        width = xiangqi ? 9 : 8;
        height = xiangqi ? 10 : 8;
        cells = new char[width * height];
        identities = new int[cells.length];
        Arrays.fill(cells, '.');
        String back = xiangqi ? "rheakaehr" : "rnbqkbnr";
        for (int x = 0; x < width; x++) {
            cells[x] = back.charAt(x);
            cells[(height - 1) * width + x] = Character.toUpperCase(back.charAt(x));
            if (!xiangqi) {
                cells[width + x] = 'p';
                cells[(height - 2) * width + x] = 'P';
            }
        }
        if (xiangqi) {
            for (int x : new int[] {1, 7}) {
                cells[2 * width + x] = 'c';
                cells[7 * width + x] = 'C';
            }
            for (int x = 0; x < width; x += 2) {
                cells[3 * width + x] = 'p';
                cells[6 * width + x] = 'P';
            }
        }
        for (int i = 0; i < cells.length; i++) {
            identities[i] = cells[i] == '.' ? -1 : i;
        }
    }

    RoomBoardPosition(RoomBoardPosition other) {
        xiangqi = other.xiangqi;
        width = other.width;
        height = other.height;
        cells = other.cells.clone();
        identities = other.identities.clone();
        turn = other.turn;
        castling = other.castling;
        enPassant = other.enPassant;
        quiet = other.quiet;
    }

    static int side(char piece) {
        return piece == '.' ? -1 : Character.isUpperCase(piece) ? 0 : 1;
    }

    List<Move> legalMoves() {
        return legalMoves(turn);
    }

    List<Move> legalMoves(int player) {
        List<Move> moves = new ArrayList<>();
        for (int from = 0; from < cells.length; from++) {
            if (side(cells[from]) != player) {
                continue;
            }
            for (int to = 0; to < cells.length; to++) {
                if (side(cells[to]) == player || Character.toLowerCase(cells[to]) == 'k'
                    || !pseudo(from, to, false)) {
                    continue;
                }
                boolean promotion = !xiangqi && Character.toLowerCase(cells[from]) == 'p'
                    && (to / width == 0 || to / width == height - 1);
                for (String choice : promotion ? List.of("q", "r", "b", "n") : List.of("")) {
                    Move move = new Move(from, to, choice);
                    RoomBoardPosition next = new RoomBoardPosition(this);
                    next.apply(move);
                    if (!next.inCheck(player)) {
                        moves.add(move);
                    }
                }
            }
        }
        return moves;
    }

    boolean inCheck(int player) {
        int king = -1;
        for (int i = 0; i < cells.length; i++) {
            if (cells[i] == (player == 0 ? 'K' : 'k')) {
                king = i;
                break;
            }
        }
        return king < 0 || attacked(king, 1 - player);
    }

    private boolean attacked(int target, int attacker) {
        for (int from = 0; from < cells.length; from++) {
            if (side(cells[from]) == attacker && pseudo(from, target, true)) {
                return true;
            }
        }
        return false;
    }

    private boolean pseudo(int from, int to, boolean attack) {
        if (from == to) {
            return false;
        }
        int x = from % width;
        int y = from / width;
        int tx = to % width;
        int ty = to / width;
        int dx = tx - x;
        int dy = ty - y;
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        char piece = Character.toLowerCase(cells[from]);
        int player = side(cells[from]);
        int forward = player == 0 ? -1 : 1;
        if (xiangqi) {
            boolean palace = tx >= 3 && tx <= 5 && (player == 0 ? ty >= 7 : ty <= 2);
            return switch (piece) {
                case 'k' -> (ax + ay == 1 && palace)
                    || (attack && dx == 0 && Character.toLowerCase(cells[to]) == 'k' && blockers(from, to) == 0);
                case 'a' -> ax == 1 && ay == 1 && palace;
                case 'e' -> ax == 2 && ay == 2 && (player == 0 ? ty >= 5 : ty <= 4)
                    && cells[(y + dy / 2) * width + x + dx / 2] == '.';
                case 'h' -> (ax == 2 && ay == 1 && cells[y * width + x + dx / 2] == '.')
                    || (ax == 1 && ay == 2 && cells[(y + dy / 2) * width + x] == '.');
                case 'r' -> (dx == 0 || dy == 0) && blockers(from, to) == 0;
                case 'c' -> (dx == 0 || dy == 0)
                    && blockers(from, to) == (attack || cells[to] != '.' ? 1 : 0);
                case 'p' -> (dx == 0 && dy == forward)
                    || (dy == 0 && ax == 1 && (player == 0 ? y <= 4 : y >= 5));
                default -> false;
            };
        }
        return switch (piece) {
            case 'p' -> pawn(from, to, forward, attack);
            case 'n' -> ax * ay == 2;
            case 'b' -> ax == ay && blockers(from, to) == 0;
            case 'r' -> (dx == 0 || dy == 0) && blockers(from, to) == 0;
            case 'q' -> (dx == 0 || dy == 0 || ax == ay) && blockers(from, to) == 0;
            case 'k' -> Math.max(ax, ay) == 1 || (!attack && castle(from, to, player));
            default -> false;
        };
    }

    private boolean pawn(int from, int to, int forward, boolean attack) {
        int dx = Math.abs(to % width - from % width);
        int dy = to / width - from / width;
        if (attack) {
            return dx == 1 && dy == forward;
        }
        if (dx == 1 && dy == forward) {
            return cells[to] != '.' || to == enPassant
                && cells[to - forward * width] == (forward == -1 ? 'p' : 'P');
        }
        return dx == 0 && cells[to] == '.' && (dy == forward || dy == 2 * forward
            && from / width == (forward == -1 ? 6 : 1) && cells[from + forward * width] == '.');
    }

    private boolean castle(int from, int to, int player) {
        int base = player == 0 ? 56 : 0;
        if (from != base + 4 || to / width != from / width || (to != base + 2 && to != base + 6)) {
            return false;
        }
        boolean kingSide = to == base + 6;
        int right = (player == 0 ? 1 : 4) * (kingSide ? 1 : 2);
        int rook = base + (kingSide ? 7 : 0);
        if ((castling & right) == 0 || cells[rook] != (player == 0 ? 'R' : 'r')
            || blockers(from, rook) != 0 || attacked(from, 1 - player)) {
            return false;
        }
        RoomBoardPosition crossing = new RoomBoardPosition(this);
        int middle = from + (kingSide ? 1 : -1);
        crossing.cells[middle] = crossing.cells[from];
        crossing.cells[from] = '.';
        return !crossing.attacked(middle, 1 - player);
    }

    private int blockers(int from, int to) {
        int dx = Integer.signum(to % width - from % width);
        int dy = Integer.signum(to / width - from / width);
        int length = Math.max(Math.abs(to % width - from % width), Math.abs(to / width - from / width));
        int count = 0;
        for (int i = 1; i < length; i++) {
            if (cells[(from / width + i * dy) * width + from % width + i * dx] != '.') {
                count++;
            }
        }
        return count;
    }

    void apply(Move move) {
        int from = move.from();
        int to = move.to();
        char piece = cells[from];
        boolean capture = cells[to] != '.';
        if (!xiangqi) {
            if (Character.toLowerCase(piece) == 'p' && to == enPassant && cells[to] == '.') {
                int captured = to + (side(piece) == 0 ? width : -width);
                cells[captured] = '.';
                identities[captured] = -1;
                capture = true;
            }
            if (Character.toLowerCase(piece) == 'k' && Math.abs(from - to) == 2) {
                int rookFrom = from / 8 * 8 + (to > from ? 7 : 0);
                int rookTo = from + (to > from ? 1 : -1);
                cells[rookTo] = cells[rookFrom];
                identities[rookTo] = identities[rookFrom];
                cells[rookFrom] = '.';
                identities[rookFrom] = -1;
            }
            if (piece == 'K') {
                castling &= ~3;
            } else if (piece == 'k') {
                castling &= ~12;
            }
            int[] rooks = {63, 56, 7, 0};
            for (int i = 0; i < rooks.length; i++) {
                if (from == rooks[i] || to == rooks[i]) {
                    castling &= ~(1 << i);
                }
            }
            enPassant = Character.toLowerCase(piece) == 'p' && Math.abs(from - to) == 16 ? (from + to) / 2 : -1;
        }
        quiet = capture || !xiangqi && Character.toLowerCase(piece) == 'p' ? 0 : quiet + 1;
        cells[to] = move.promotion().isEmpty() ? piece
            : side(piece) == 0 ? Character.toUpperCase(move.promotion().charAt(0)) : move.promotion().charAt(0);
        identities[to] = identities[from];
        cells[from] = '.';
        identities[from] = -1;
        turn = 1 - turn;
    }

    boolean protectedCapture(Move capture) {
        int defenderSide = side(cells[capture.to()]);
        RoomBoardPosition captured = new RoomBoardPosition(this);
        captured.apply(capture);
        for (int defender = 0; defender < cells.length; defender++) {
            if (defender == capture.to() || side(cells[defender]) != defenderSide
                || !captured.pseudo(defender, capture.to(), true)) {
                continue;
            }
            RoomBoardPosition recaptured = new RoomBoardPosition(captured);
            recaptured.apply(new Move(defender, capture.to(), ""));
            if (!recaptured.inCheck(defenderSide)) {
                return true;
            }
        }
        return false;
    }

    String key() {
        boolean legalEnPassant = enPassant >= 0 && legalMoves().stream()
            .anyMatch(move -> move.to() == enPassant && Character.toLowerCase(cells[move.from()]) == 'p');
        return String.valueOf(cells) + turn + (xiangqi ? "" : ":" + castling + ":" + (legalEnPassant ? enPassant : -1));
    }

    boolean canMate(int player) {
        if (xiangqi) {
            for (char piece : cells) {
                if (side(piece) == player && "rhcp".indexOf(Character.toLowerCase(piece)) >= 0) {
                    return true;
                }
            }
            return false;
        }
        int minors = 0;
        int enemyPieces = 0;
        boolean knight = false;
        int bishopColor = -1;
        boolean oppositeBishops = false;
        for (int i = 0; i < cells.length; i++) {
            char piece = Character.toLowerCase(cells[i]);
            if (side(cells[i]) != player) {
                if (side(cells[i]) >= 0 && piece != 'k') {
                    enemyPieces++;
                }
                continue;
            }
            if ("pqr".indexOf(piece) >= 0) {
                return true;
            }
            if (piece == 'n' || piece == 'b') {
                minors++;
                knight |= piece == 'n';
                if (piece == 'b') {
                    int color = (i % width + i / width) % 2;
                    oppositeBishops |= bishopColor >= 0 && bishopColor != color;
                    bishopColor = color;
                }
            }
        }
        if (minors == 0) {
            return false;
        }
        if (enemyPieces == 0) {
            return knight ? minors >= 2 : oppositeBishops;
        }
        // Các tượng cùng màu ở cả hai bên không thể tạo thế chiếu hết.
        if (!knight && !oppositeBishops) {
            boolean otherMaterial = false;
            for (int i = 0; i < cells.length; i++) {
                char piece = Character.toLowerCase(cells[i]);
                if (side(cells[i]) == 1 - player && piece != 'k'
                    && (piece != 'b' || (i % width + i / width) % 2 != bishopColor)) {
                    otherMaterial = true;
                }
            }
            return otherMaterial;
        }
        return true;
    }
}
