package vn.com.coreacademy.chatroom.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Chốt vị trí ở hạn vòng trước khi áp dụng bất kỳ gói di chuyển mới nào. */
final class RoomColorFloor {
    record Player(String user, String name, double x, double y) {}

    private final Set<String> queued = new LinkedHashSet<>();
    private final Map<String, String> alive = new LinkedHashMap<>();
    private final Set<String> eliminated = new LinkedHashSet<>();
    private List<String> winners = List.of();
    private String phase = "waiting";
    private String id = UUID.randomUUID().toString();
    private String target = "";
    private int round;
    private long deadline;

    boolean join(String user, boolean near) {
        return near && !alive.containsKey(user) && queued.add(user);
    }

    void leave(String user) {
        queued.remove(user);
    }

    boolean tick(long now, List<Player> present, List<ChatRoomMap.ColorTile> tiles) {
        if ("waiting".equals(phase) && queued.size() >= 2) {
            phase = "countdown";
            deadline = now + 5000;
            return true;
        }
        if ("waiting".equals(phase) || now < deadline) {
            return false;
        }
        if ("countdown".equals(phase)) {
            for (Player player : present) {
                if (queued.remove(player.user())) {
                    alive.put(player.user(), player.name());
                }
            }
            if (alive.size() < 2) {
                queued.addAll(alive.keySet());
                alive.clear();
                phase = "waiting";
                deadline = 0;
                return true;
            }
            id = UUID.randomUUID().toString();
            eliminated.clear();
            winners = List.of();
            round = 0;
            nextRound(now, tiles);
            return true;
        }
        if ("running".equals(phase)) {
            Map<String, Player> positions = new LinkedHashMap<>();
            present.forEach(player -> positions.put(player.user(), player));
            for (String user : List.copyOf(alive.keySet())) {
                Player player = positions.get(user);
                boolean safe = player != null && tiles.stream().anyMatch(tile ->
                    tile.color().equals(target) && contains(tile.area(), player.x(), player.y()));
                if (!safe) {
                    alive.remove(user);
                    eliminated.add(user);
                }
            }
            if (alive.size() <= 1 || round == 5) {
                winners = List.copyOf(alive.values());
                alive.clear();
                phase = "finished";
                deadline = now + 5000;
            } else {
                nextRound(now, tiles);
            }
            return true;
        }
        phase = "waiting";
        deadline = 0;
        return true;
    }

    private void nextRound(long now, List<ChatRoomMap.ColorTile> tiles) {
        List<String> colors = tiles.stream().map(ChatRoomMap.ColorTile::color).distinct().toList();
        target = colors.get(ThreadLocalRandom.current().nextInt(colors.size()));
        phase = "running";
        round++;
        deadline = now + 5000;
    }

    static boolean contains(ChatRoomMap.Area area, double x, double y) {
        // Trái/trên thuộc ô, phải/dưới thuộc ô kế tiếp. Mép ngoài phải/dưới nằm ngoài sàn.
        return x >= area.x() && x < area.x() + area.w() && y >= area.y() && y < area.y() + area.h();
    }

    Map<String, Object> snapshot(String user, long now) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("type", "floor-state");
        state.put("id", id);
        state.put("phase", phase);
        state.put("round", round);
        state.put("target", target);
        state.put("deadline", deadline);
        state.put("serverTime", now);
        state.put("players", new ArrayList<>(alive.values()));
        state.put("queued", queued.size());
        state.put("joined", queued.contains(user));
        state.put("alive", alive.containsKey(user));
        state.put("eliminated", eliminated.contains(user));
        state.put("winners", winners);
        return state;
    }
}
