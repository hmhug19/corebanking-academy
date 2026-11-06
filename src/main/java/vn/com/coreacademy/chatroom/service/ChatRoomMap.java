package vn.com.coreacademy.chatroom.service;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class ChatRoomMap {
    public record Point(double x, double y) {
    }

    public record Furniture(String kind, double x, double y, double w, double h) {
    }

    public record Layout(int width, int height, double speed, double radius, Point spawn, List<Furniture> objects) {
    }

    private final Layout layout;

    public ChatRoomMap(ObjectMapper mapper) throws IOException {
        try (var stream = new ClassPathResource("chat-room/room.json").getInputStream()) {
            layout = mapper.readValue(stream, Layout.class);
        }
    }

    public Layout layout() {
        return layout;
    }

    public boolean walkable(double x, double y) {
        double r = layout.radius();
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 32 + r || y < 48 + r
            || x > layout.width() - 32 - r || y > layout.height() - 32 - r) {
            return false;
        }
        return layout.objects().stream().noneMatch(o -> x + r > o.x() && x - r < o.x() + o.w()
            && y + r > o.y() && y - r < o.y() + o.h());
    }

    public boolean clearPath(double x, double y, double toX, double toY) {
        if (!walkable(toX, toY)) {
            return false;
        }
        int steps = (int) Math.ceil(Math.hypot(toX - x, toY - y) / 4);
        for (int i = 1; i <= steps; i++) {
            if (!walkable(x + (toX - x) * i / steps, y + (toY - y) * i / steps)) {
                return false;
            }
        }
        return true;
    }
}
