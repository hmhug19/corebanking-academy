package vn.com.coreacademy.learning.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.dto.FlowModel;
import vn.com.coreacademy.learning.dto.FlowModel.FlowObject;
import vn.com.coreacademy.learning.dto.FlowModel.FlowStep;

/**
 * Quy tắc của tài liệu Mô phỏng luồng và cách đọc nó thành văn bản cho trợ lý. Lỗi trả theo đường dẫn
 * {@code flow.objects[i].field} / {@code flow.steps[i].field}.
 */
public final class FlowModelRules {
    public static final int VERSION = 1;
    public static final int MAX_OBJECTS = 30;
    public static final int MAX_STEPS = 100;
    public static final int MAX_NAME_LENGTH = 60;
    public static final int MAX_TITLE_LENGTH = 255;
    public static final int MAX_DESCRIPTION_LENGTH = 2_000;
    public static final int GRID_LIMIT = 40;
    public static final Set<String> KINDS = Set.of("customer", "staff", "channel", "system", "database", "queue",
        "gateway", "document", "external", "decision");
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,20}");
    private static final String INVALID = "INVALID_VALUE";

    private FlowModelRules() {
    }

    /** Kiểm tra và trả bản đã chuẩn hóa (tên, tiêu đề, mô tả được cắt khoảng trắng; mô tả rỗng thành null). */
    public static FlowModel validated(FlowModel flow) {
        if (flow == null) {
            throw BusinessException.validation("flow", "REQUIRED", "Khối Mô phỏng luồng cần dữ liệu đối tượng và bước.");
        }
        if (flow.version() == null || flow.version() != VERSION) {
            throw BusinessException.validation("flow.version", INVALID, "Phiên bản dữ liệu Mô phỏng luồng không hợp lệ.");
        }
        List<FlowObject> objects = flow.objects() == null ? List.of() : flow.objects();
        List<FlowStep> steps = flow.steps() == null ? List.of() : flow.steps();
        if (objects.size() > MAX_OBJECTS) {
            throw BusinessException.validation("flow.objects", "TOO_MANY", "Tối đa " + MAX_OBJECTS + " đối tượng.");
        }
        if (steps.size() > MAX_STEPS) {
            throw BusinessException.validation("flow.steps", "TOO_MANY", "Tối đa " + MAX_STEPS + " bước chuyển.");
        }

        List<FlowObject> cleanObjects = new ArrayList<>(objects.size());
        Set<String> ids = new HashSet<>();
        Set<String> cells = new HashSet<>();
        for (int i = 0; i < objects.size(); i++) {
            FlowObject o = objects.get(i);
            String at = "flow.objects[" + i + "]";
            if (o == null) {
                throw BusinessException.validation(at, "REQUIRED", "Thiếu đối tượng.");
            }
            id(at, o.id(), ids, "Mã đối tượng");
            String name = text(at + ".name", o.name(), MAX_NAME_LENGTH, "Tên đối tượng", true);
            if (o.kind() == null || !KINDS.contains(o.kind())) {
                throw BusinessException.validation(at + ".kind", INVALID, "Loại đối tượng không hợp lệ.");
            }
            cell(at + ".x", o.x());
            cell(at + ".z", o.z());
            if (!cells.add(o.x() + "," + o.z())) {
                throw BusinessException.validation(at + ".x", "DUPLICATE", "Hai đối tượng không được đặt cùng một ô.");
            }
            cleanObjects.add(new FlowObject(o.id(), name, o.kind(), o.x(), o.z()));
        }

        List<FlowStep> cleanSteps = new ArrayList<>(steps.size());
        Set<String> stepIds = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            FlowStep s = steps.get(i);
            String at = "flow.steps[" + i + "]";
            if (s == null) {
                throw BusinessException.validation(at, "REQUIRED", "Thiếu bước chuyển.");
            }
            id(at, s.id(), stepIds, "Mã bước");
            if (s.from() == null || !ids.contains(s.from())) {
                throw BusinessException.validation(at + ".from", INVALID, "Bước chuyển phải bắt đầu từ một đối tượng có sẵn.");
            }
            if (s.to() != null && (!ids.contains(s.to()) || s.to().equals(s.from()))) {
                throw BusinessException.validation(at + ".to", INVALID,
                    "Bước chuyển phải tới một đối tượng khác, hoặc để trống khi đối tượng tự xử lý.");
            }
            String title = text(at + ".title", s.title(), MAX_TITLE_LENGTH, "Tiêu đề bước", true);
            String description = text(at + ".description", s.description(), MAX_DESCRIPTION_LENGTH, "Mô tả bước",
                false);
            cleanSteps.add(new FlowStep(s.id(), s.from(), s.to(), title, description));
        }

        return new FlowModel(VERSION, cleanObjects, cleanSteps);
    }

    /** Số bước chuyển; 0 khi không có tài liệu. */
    public static int stepCount(FlowModel flow) {
        return flow == null || flow.steps() == null ? 0 : flow.steps().size();
    }

    /** Các bước theo thứ tự, mỗi bước một dòng: {@code n. tiêu đề (từ → tới | tự xử lý): mô tả}. */
    public static String text(FlowModel flow) {
        if (flow == null || flow.steps() == null) {
            return "";
        }
        Map<String, String> names = new HashMap<>();
        if (flow.objects() != null) {
            flow.objects().forEach(o -> names.put(o.id(), o.name()));
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < flow.steps().size(); i++) {
            FlowStep s = flow.steps().get(i);
            text.append(i + 1).append(". ").append(s.title()).append(" (")
                .append(names.getOrDefault(s.from(), "?")).append(" → ")
                .append(s.to() == null ? "tự xử lý" : names.getOrDefault(s.to(), "?")).append(')');
            if (s.description() != null && !s.description().isBlank()) {
                text.append(": ").append(s.description());
            }
            text.append('\n');
        }

        return text.toString();
    }

    private static void id(String at, String id, Set<String> seen, String label) {
        if (id == null || !ID.matcher(id).matches()) {
            throw BusinessException.validation(at + ".id", INVALID,
                label + " gồm 1-20 ký tự a-z, 0-9, gạch ngang hoặc gạch dưới.");
        }
        if (!seen.add(id)) {
            throw BusinessException.validation(at + ".id", "DUPLICATE", label + " bị trùng.");
        }
    }

    private static String text(String field, String value, int max, String label, boolean required) {
        String clean = value == null ? "" : value.strip();
        if (clean.isEmpty()) {
            if (required) {
                throw BusinessException.validation(field, "REQUIRED", label + " không được để trống.");
            }
            return null;
        }
        if (clean.length() > max) {
            throw BusinessException.validation(field, "TOO_LONG", label + " tối đa " + max + " ký tự.");
        }
        return clean;
    }

    private static void cell(String field, Integer value) {
        if (value == null || value < -GRID_LIMIT || value > GRID_LIMIT) {
            throw BusinessException.validation(field, INVALID,
                "Vị trí đối tượng phải là số nguyên từ -" + GRID_LIMIT + " đến " + GRID_LIMIT + ".");
        }
    }
}
