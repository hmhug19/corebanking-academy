package vn.com.coreacademy.community.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import vn.com.coreacademy.common.exception.BusinessException;

/** Canonical JSON chỉ dùng so sánh yêu cầu, không lưu prompt hay nội dung vào log. */
@Component
@RequiredArgsConstructor
public class CommunityPayload {
    private static final Pattern HTML = Pattern.compile("(?i)<(?:/?[a-z]|!|\\?)");
    private static final Pattern UNSAFE_SCHEME = Pattern.compile("(?i)(?:javascript|data|vbscript):");
    private static final Pattern INTERNAL_IMAGE = Pattern.compile(
        "!\\[[^\\[\\]\\r\\n]*]\\(\\s*/api/v1/community/revision-assets/[1-9][0-9]*/content\\s*\\)");
    private final ObjectMapper objectMapper;

    public String hash(String operation, Object payload) {
        try {
            JsonNode canonical = canonical(objectMapper.valueToTree(payload));
            byte[] bytes = (operation + "\n" + objectMapper.writeValueAsString(canonical))
                .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Cannot canonicalize Community request", exception);
        }
    }

    private JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            node.fieldNames().forEachRemaining(fields::add);
            fields.stream().sorted().forEach(name -> result.set(name, canonical(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            var result = objectMapper.createArrayNode();
            node.forEach(child -> result.add(canonical(child)));
            return result;
        }
        return node;
    }

    public JsonNode context(JsonNode input, String kind) {
        JsonNode value = input == null || input.isNull() ? objectMapper.createObjectNode() : input;
        Set<String> allowed = switch (kind) {
            case "wiki" -> Set.of("technologies", "environment", "limitations", "wiki");
            case "radar" -> Set.of("technologies", "environment", "limitations", "radar");
            case "recording" -> Set.of("technologies", "environment", "limitations", "recording");
            case "architecture_review" -> Set.of("technologies", "environment", "limitations", "review");
            case "lesson_learned" -> Set.of("technologies", "environment", "limitations", "lessonLearned");
            default -> Set.of("technologies", "environment", "limitations");
        };
        if (!value.isObject()) {
            throw BusinessException.validation("context", "INVALID_VALUE", "Context phải là object.");
        }
        value.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                throw BusinessException.validation("context", "UNKNOWN_FIELD", "Context có trường chưa được hỗ trợ.");
            }
        });
        text(value, "environment", 255);
        text(value, "limitations", 5_000);
        if (value.has("technologies")) {
            JsonNode technologies = value.get("technologies");
            if (!technologies.isArray() || technologies.size() > 20) {
                throw BusinessException.validation("context.technologies", "INVALID_VALUE", "Tối đa 20 công nghệ.");
            }
            for (JsonNode technology : technologies) {
                if (!technology.isObject() || !technology.hasNonNull("name") || technology.size() > 2) {
                    throw BusinessException.validation("context.technologies", "INVALID_VALUE", "Thiếu tên công nghệ.");
                }
                technology.fieldNames().forEachRemaining(name -> {
                    if (!Set.of("name", "version").contains(name)) {
                        throw BusinessException.validation("context.technologies", "UNKNOWN_FIELD",
                            "Trường không hợp lệ.");
                    }
                });
                text(technology, "name", 100);
                text(technology, "version", 100);
            }
        }
        CommunityDocumentContext.validate(value, kind, false);
        return value.deepCopy();
    }

    private void text(JsonNode node, String name, int limit) {
        if (node.has(name) && (!node.get(name).isTextual() || node.get(name).textValue().length() > limit)) {
            throw BusinessException.validation("context." + name, "INVALID_VALUE", "Giá trị context không hợp lệ.");
        }
    }

    /** HTML bị từ chối ngoài fenced/inline code; FE vẫn phải escape code và sanitize khi render Markdown. */
    public void markdown(String body) {
        String visible = visibleProse(body);
        String normalizedLinks = HtmlUtils.htmlUnescape(visible).replaceAll("[\\x00-\\x20]", "");
        if (HTML.matcher(visible).find() || UNSAFE_SCHEME.matcher(normalizedLinks).find()
            || INTERNAL_IMAGE.matcher(visible).replaceAll("").contains("![")) {
            throw BusinessException.validation("bodyMarkdown", "UNSAFE_MARKDOWN",
                "Không nhúng HTML/script hoặc ảnh ngoài hệ thống; ảnh dùng đường dẫn revision-assets nội bộ.");
        }
        mentions(body);
    }

    public Set<String> mentions(String body) {
        Set<String> users = new LinkedHashSet<>();
        var matcher = Pattern.compile("(?<![\\\\!])@\\[[^\\]\\r\\n]{1,255}]\\(user:([A-Za-z0-9_-]{1,20})\\)")
            .matcher(visibleProse(body));
        while (matcher.find()) {
            users.add(matcher.group(1));
            if (users.size() > 30) {
                throw BusinessException.validation("bodyMarkdown", "TOO_MANY_MENTIONS",
                    "Tối đa 30 người được nhắc tên.");
            }
        }
        return users;
    }

    private String visibleProse(String body) {
        boolean fenced = false;
        char fence = 0;
        int width = 0;
        StringBuilder prose = new StringBuilder();
        for (String line : body.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                char marker = trimmed.charAt(0);
                int count = 0;
                while (count < trimmed.length() && trimmed.charAt(count) == marker) {
                    count++;
                }
                if (!fenced) {
                    if (marker == '`' && trimmed.substring(count).contains("`")) {
                        prose.append(line).append('\n');
                        continue;
                    }
                    fenced = true;
                    fence = marker;
                    width = count;
                } else if (marker == fence && count >= width && trimmed.substring(count).isBlank()) {
                    fenced = false;
                }
                continue;
            }
            if (!fenced && !line.startsWith("    ") && !line.startsWith("\t")) {
                prose.append(line).append('\n');
            }
        }
        return withoutCodeSpans(prose.toString());
    }

    /** Chỉ bỏ code span có delimiter cùng số backtick; delimiter gần giống không được che HTML khỏi validation. */
    private String withoutCodeSpans(String source) {
        StringBuilder prose = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            if (source.charAt(index) != '`' || escaped(source, index)) {
                prose.append(source.charAt(index++));
                continue;
            }
            int width = tickRun(source, index);
            int closing = index + width;
            boolean found = false;
            while (closing < source.length()) {
                if (source.charAt(closing) == '`') {
                    int candidate = tickRun(source, closing);
                    if (candidate == width) {
                        found = true;
                        break;
                    }
                    closing += candidate;
                } else {
                    closing++;
                }
            }
            if (found) {
                index = closing + width;
            } else {
                prose.append(source, index, index + width);
                index += width;
            }
        }
        return prose.toString();
    }

    private int tickRun(String source, int start) {
        int end = start;
        while (end < source.length() && source.charAt(end) == '`') {
            end++;
        }
        return end - start;
    }

    private boolean escaped(String source, int index) {
        int slashes = 0;
        while (index > 0 && source.charAt(--index) == '\\') {
            slashes++;
        }
        return slashes % 2 != 0;
    }
}
