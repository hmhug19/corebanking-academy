package vn.com.coreacademy.chatroom.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class ChatRoomMap {
    public record Point(double x, double y) {
    }

    public record Furniture(String kind, double x, double y, double w, double h, String facing) {
    }

    public record Area(double x, double y, double w, double h) {
        public boolean contains(double px, double py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    public record CaroTable(int desk, List<Integer> chairs) {
    }

    public record Layout(int width, int height, double speed, double radius, Point spawn, List<Furniture> objects,
        Area activityArea, Area diceArea, List<CaroTable> caroTables) {
    }

    private final Layout layout;
    private final List<Point> corners;
    private final boolean[][] visibleCorners;

    public ChatRoomMap(ObjectMapper mapper) throws IOException {
        try (var stream = new ClassPathResource("chat-room/room.json").getInputStream()) {
            layout = mapper.readValue(stream, Layout.class);
        }
        List<Point> nodes = new ArrayList<>();
        double margin = layout.radius() + 3;
        for (Furniture object : layout.objects()) {
            for (double x : new double[] {object.x() - margin, object.x() + object.w() + margin}) {
                for (double y : new double[] {object.y() - margin, object.y() + object.h() + margin}) {
                    if (walkable(x, y)) {
                        nodes.add(new Point(x, y));
                    }
                }
            }
        }
        corners = List.copyOf(nodes);
        visibleCorners = new boolean[corners.size()][corners.size()];
        for (int i = 0; i < corners.size(); i++) {
            Point from = corners.get(i);
            for (int j = 0; j < i; j++) {
                Point to = corners.get(j);
                boolean visible = clearPath(from.x(), from.y(), to.x(), to.y());
                visibleCorners[i][j] = visibleCorners[j][i] = visible;
            }
        }
    }

    public Layout layout() {
        return layout;
    }

    public int caroTable(Integer chair) {
        if (chair != null) {
            for (int i = 0; i < layout.caroTables().size(); i++) {
                if (layout.caroTables().get(i).chairs().contains(chair)) {
                    return i + 1;
                }
            }
        }
        return 0;
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
        if (!walkable(x, y) || !walkable(toX, toY)) {
            return false;
        }
        double r = layout.radius();
        for (Furniture object : layout.objects()) {
            double left = object.x() - r;
            double right = object.x() + object.w() + r;
            double top = object.y() - r;
            double bottom = object.y() + object.h() + r;
            if (Math.max(x, toX) <= left || Math.min(x, toX) >= right
                || Math.max(y, toY) <= top || Math.min(y, toY) >= bottom) {
                continue;
            }
            double enter = 0;
            double exit = 1;
            if (toX != x) {
                double a = (left - x) / (toX - x);
                double b = (right - x) / (toX - x);
                enter = Math.max(enter, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
            }
            if (toY != y) {
                double a = (top - y) / (toY - y);
                double b = (bottom - y) / (toY - y);
                enter = Math.max(enter, Math.min(a, b));
                exit = Math.min(exit, Math.max(a, b));
            }
            if (enter < exit) {
                return false;
            }
        }
        return true;
    }

    /** Search the visible corners of furniture expanded by the avatar's radius. */
    public List<Point> path(Point from, Point to) {
        if (clearPath(from.x(), from.y(), to.x(), to.y())) {
            return List.of(to);
        }
        if (!walkable(to.x(), to.y())) {
            return List.of();
        }
        List<Point> nodes = new ArrayList<>(corners.size() + 2);
        nodes.add(from);
        nodes.add(to);
        nodes.addAll(corners);
        double[] cost = new double[nodes.size()];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        cost[0] = 0;
        int[] previous = new int[nodes.size()];
        boolean[] visited = new boolean[nodes.size()];
        PriorityQueue<Step> frontier = new PriorityQueue<>();
        frontier.add(new Step(0, distance(from, to)));
        while (!frontier.isEmpty()) {
            int current = frontier.remove().index();
            if (visited[current]) {
                continue;
            }
            if (current == 1) {
                List<Point> path = new ArrayList<>();
                for (int i = 1; i != 0; i = previous[i]) {
                    path.add(nodes.get(i));
                }
                Collections.reverse(path);
                return path;
            }
            visited[current] = true;
            Point origin = nodes.get(current);
            for (int i = 1; i < nodes.size(); i++) {
                Point next = nodes.get(i);
                double nextCost = cost[current] + distance(origin, next);
                if (!visited[i] && nextCost < cost[i] && (current >= 2 && i >= 2
                    ? visibleCorners[current - 2][i - 2] : clearPath(origin.x(), origin.y(), next.x(), next.y()))) {
                    cost[i] = nextCost;
                    previous[i] = current;
                    frontier.add(new Step(i, nextCost + distance(next, to)));
                }
            }
        }
        return List.of();
    }

    public static double distance(Point a, Point b) {
        return Math.hypot(b.x() - a.x(), b.y() - a.y());
    }

    private record Step(int index, double estimate) implements Comparable<Step> {
        @Override
        public int compareTo(Step other) {
            return Double.compare(estimate, other.estimate);
        }
    }
}
