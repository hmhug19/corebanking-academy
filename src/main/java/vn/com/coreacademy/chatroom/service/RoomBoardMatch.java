package vn.com.coreacademy.chatroom.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Ván cờ và đồng hồ được thay đổi trong monitor của ChatRoomService. */
final class RoomBoardMatch {
    record Player(String userId, String name) {}
    private record Ply(int player, boolean check, Set<Integer> targets, Set<Integer> escaped) {}

    final UUID id = UUID.randomUUID();
    final UUID transfer = UUID.randomUUID();
    final Player[] players;
    final long points;
    final long increment;
    final List<String> history = new ArrayList<>();
    final List<RoomBoardPosition> positions = new ArrayList<>();
    final Map<String, Integer> repetitions = new HashMap<>();
    private final List<Ply> plies = new ArrayList<>();
    private final long[] remaining;
    RoomBoardPosition position;
    long startedAt;
    long finishedAt;
    Integer winner;
    String reason;
    Integer drawOffer;
    Integer mustChange;
    private String warnedKey;
    private int warnedLevel;
    private final int[] technicalFaults = new int[2];
    private final int[] checksSinceCapture = new int[2];
    boolean settled;

    RoomBoardMatch(boolean xiangqi, Player first, Player second, long points, long baseMillis,
                   long incrementMillis, long now) {
        this.position = new RoomBoardPosition(xiangqi);
        this.players = new Player[] {first, second};
        this.points = points;
        this.increment = incrementMillis;
        this.remaining = new long[] {baseMillis, baseMillis};
        startedAt = now;
        positions.add(new RoomBoardPosition(position));
        repetitions.put(position.key(), 1);
    }

    int side(String user) {
        for (int i = 0; i < players.length; i++) {
            if (players[i].userId().equals(user)) {
                return i;
            }
        }
        return -1;
    }

    long clock(int player, long now) {
        return Math.max(0, remaining[player] - (reason == null && position.turn == player ? now - startedAt : 0));
    }

    boolean expire(long now) {
        if (reason != null || clock(position.turn, now) > 0) {
            return false;
        }
        int opponent = 1 - position.turn;
        finish(position.xiangqi || position.canMate(opponent) ? opponent : null, "timeout", now);
        return true;
    }

    boolean move(String user, String matchId, int version, int from, int to, String promotion, long now) {
        if (!accepts(user, matchId, version) || side(user) != position.turn || expire(now)) {
            return false;
        }
        RoomBoardPosition.Move selected = position.legalMoves().stream()
            .filter(move -> move.from() == from && move.to() == to && move.promotion().equals(promotion))
            .findFirst().orElse(null);
        if (selected == null) {
            return false;
        }
        int player = position.turn;
        Set<Integer> targets = position.xiangqi ? chaseTargets(position, player) : Set.of();
        Set<Integer> priorTargets = plies.isEmpty() ? Set.of() : plies.get(plies.size() - 1).targets();
        boolean capture = position.cells[to] != '.';
        remaining[player] = clock(player, now) + increment;
        position.apply(selected);
        startedAt = now;
        if (drawOffer != null && drawOffer != player) {
            drawOffer = null;
        }
        boolean check = position.inCheck(position.turn);
        Set<Integer> newTargets = position.xiangqi ? chaseTargets(position, player) : new HashSet<>();
        Set<Integer> escaped = new HashSet<>(priorTargets);
        escaped.removeAll(position.xiangqi ? chaseTargets(position, 1 - player) : Set.of());
        newTargets.removeAll(targets);
        // Theo dõi danh tính quân bị đuổi qua toàn chu kỳ.
        if (position.xiangqi && !capture && !check) {
            newTargets.addAll(chaseTargets(position, player));
        }
        plies.add(new Ply(player, check, Set.copyOf(newTargets), escaped));
        history.add(coordinate(from) + "–" + coordinate(to) + promotion + (check ? "+" : ""));
        positions.add(new RoomBoardPosition(position));
        int count = repetitions.merge(position.key(), 1, Integer::sum);
        if (capture) {
            checksSinceCapture[0] = checksSinceCapture[1] = 0;
        }
        if (capture || mustChange != null && mustChange == player
            && (warnedLevel == 2 ? !check : newTargets.isEmpty())) {
            mustChange = null;
            warnedKey = null;
        }
        if (check) {
            checksSinceCapture[player]++;
        }
        adjudicate(count, now);
        return true;
    }

    boolean accepts(String user, String matchId, int version) {
        return reason == null && side(user) >= 0 && id.toString().equals(matchId) && history.size() == version;
    }

    boolean action(String user, String matchId, int version, String action,
                   Integer from, Integer to, String promotion, long now) {
        if (!accepts(user, matchId, version) || expire(now)) {
            return false;
        }
        int player = side(user);
        switch (action) {
            case "resign" -> finish(position.xiangqi || position.canMate(1 - player) ? 1 - player : null, "resign", now);
            case "offer-draw" -> {
                if (history.size() < 2) {
                    return false;
                }
                drawOffer = player;
            }
            case "accept-draw" -> {
                if (drawOffer == null || drawOffer == player) {
                    return false;
                }
                finish(null, "agreement", now);
            }
            case "claim-draw" -> {
                if (player != position.turn) {
                    return false;
                }
                return claim(from, to, promotion, now);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private boolean claim(Integer from, Integer to, String promotion, long now) {
        RoomBoardPosition claimed = new RoomBoardPosition(position);
        RoomBoardPosition.Move planned = null;
        if (from != null && to != null) {
            planned = claimed.legalMoves().stream().filter(move -> move.from() == from && move.to() == to
                && move.promotion().equals(promotion)).findFirst().orElse(null);
            if (planned == null) {
                return false;
            }
            claimed.apply(planned);
        }
        int occurrences = repetitions.getOrDefault(claimed.key(), 0) + (planned == null ? 0 : 1);
        if (!position.xiangqi && (occurrences >= 3 || claimed.quiet >= 100)) {
            finish(null, occurrences >= 3 ? "threefold" : "fifty-moves", now);
            return true;
        }
        if (position.xiangqi) {
            if (naturalCount() >= 100) {
                finish(null, "natural-movecount", now);
                return true;
            }
            technicalFaults[position.turn]++;
            remaining[position.turn] = clock(position.turn, now) - 300_000;
            startedAt = now;
            if (technicalFaults[position.turn] >= 2 || remaining[position.turn] <= 0) {
                finish(1 - position.turn, "invalid-claim", now);
            }
        }
        // Yêu cầu hòa FIDE sai cộng hai phút cho đối phương.
        if (!position.xiangqi) {
            remaining[1 - position.turn] += 120_000;
            drawOffer = position.turn;
            if (planned != null) {
                move(players[position.turn].userId(), id.toString(), history.size(),
                    planned.from(), planned.to(), planned.promotion(), now);
            }
        }
        return true;
    }

    private void adjudicate(int occurrences, long now) {
        List<RoomBoardPosition.Move> legal = position.legalMoves();
        if (legal.isEmpty()) {
            finish(position.xiangqi || position.inCheck(position.turn) ? 1 - position.turn : null,
                position.inCheck(position.turn) ? "checkmate" : "stalemate", now);
            return;
        }
        if (!position.canMate(0) && !position.canMate(1)) {
            finish(null, "dead-position", now);
            return;
        }
        if (!position.xiangqi) {
            if (occurrences >= 5 || position.quiet >= 150) {
                finish(null, occurrences >= 5 ? "fivefold" : "seventy-five-moves", now);
            }
        } else if (occurrences >= 3) {
            judgeCycle(now);
        }
        if (position.xiangqi && reason == null && naturalCount() >= 100) {
            finish(null, "natural-movecount", now);
        }
    }

    private int naturalCount() {
        return position.quiet - Math.max(0, checksSinceCapture[0] - 10)
            - Math.max(0, checksSinceCapture[1] - 10);
    }

    private void judgeCycle(long now) {
        String current = position.key();
        int earlier = -1;
        int seen = 0;
        for (int i = positions.size() - 1; i >= 0; i--) {
            if (positions.get(i).key().equals(current) && ++seen == 3) {
                earlier = i;
                break;
            }
        }
        if (earlier < 0) {
            return;
        }
        int[] levels = new int[2];
        for (int player = 0; player < 2; player++) {
            boolean allCheck = true;
            boolean allChase = true;
            Set<Integer> common = null;
            for (int i = earlier; i < plies.size(); i++) {
                Ply ply = plies.get(i);
                if (ply.player() != player) {
                    continue;
                }
                allCheck &= ply.check();
                Set<Integer> answered = new HashSet<>(ply.targets());
                answered.retainAll(plies.get(i + 1 < plies.size() ? i + 1 : earlier).escaped());
                allChase &= !ply.check() && !answered.isEmpty();
                if (common == null) {
                    common = answered;
                } else {
                    common.retainAll(answered);
                }
            }
            levels[player] = allCheck ? 2 : allChase && common != null && !common.isEmpty() ? 1 : 0;
        }
        if (levels[0] == levels[1]) {
            // WXF 3.2.B requires four occurrences when neither side repeats an offence.
            if (levels[0] > 0 || repetitions.getOrDefault(current, 0) >= 4) {
                finish(null, "repetition", now);
            }
            return;
        }
        int offender = levels[0] > levels[1] ? 0 : 1;
        if (mustChange != null && mustChange == offender && current.equals(warnedKey)) {
            finish(1 - offender, levels[offender] == 2 ? "perpetual-check" : "perpetual-chase", now);
        } else if (mustChange == null || mustChange != offender) {
            mustChange = offender;
            warnedKey = current;
            warnedLevel = levels[offender];
        }
    }

    private Set<Integer> chaseTargets(RoomBoardPosition board, int player) {
        Set<Integer> targets = new HashSet<>();
        for (RoomBoardPosition.Move capture : board.legalMoves(player)) {
            char attacker = Character.toLowerCase(board.cells[capture.from()]);
            char victim = Character.toLowerCase(board.cells[capture.to()]);
            int row = capture.to() / board.width;
            if (victim == '.' || attacker == 'k' || attacker == 'p'
                || victim == 'p' && (player == 0 ? row <= 4 : row >= 5)) {
                continue;
            }
            boolean exchange = attacker == victim && board.legalMoves(1 - player).stream()
                .anyMatch(reply -> reply.from() == capture.to() && reply.to() == capture.from());
            boolean defended = !((attacker == 'h' || attacker == 'c') && victim == 'r')
                && board.protectedCapture(capture);
            if (!exchange && !defended) {
                targets.add(board.identities[capture.to()]);
            }
        }
        return targets;
    }

    private String coordinate(int cell) {
        return String.valueOf((char) ('a' + cell % position.width)) + (position.height - cell / position.width);
    }

    private void finish(Integer winningSide, String result, long now) {
        remaining[position.turn] = clock(position.turn, now);
        winner = winningSide;
        reason = result;
        finishedAt = now;
        settled = winner == null;
    }

    Map<String, Object> snapshot(String user, long now) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", id.toString());
        view.put("board", String.valueOf(position.cells));
        view.put("side", side(user));
        view.put("turn", position.turn);
        view.put("version", history.size());
        view.put("players", List.of(players[0].name(), players[1].name()));
        view.put("clocks", List.of(clock(0, now), clock(1, now)));
        view.put("serverTime", now);
        view.put("check", position.inCheck(position.turn));
        view.put("history", List.copyOf(history));
        view.put("legal", reason == null ? position.legalMoves() : List.of());
        view.put("result", reason == null ? "playing" : winner == null ? "draw" : String.valueOf(winner));
        view.put("reason", reason == null ? "" : reason);
        view.put("drawOffer", drawOffer == null ? -1 : drawOffer);
        view.put("mustChange", mustChange == null ? -1 : mustChange);
        if (!plies.isEmpty()) {
            RoomBoardPosition previous = positions.get(positions.size() - 2);
            List<Integer> changed = new ArrayList<>();
            for (int i = 0; i < previous.cells.length; i++) {
                if (previous.cells[i] != position.cells[i]) {
                    changed.add(i);
                }
            }
            view.put("lastCells", changed);
        } else {
            view.put("lastCells", List.of());
        }
        return view;
    }
}
