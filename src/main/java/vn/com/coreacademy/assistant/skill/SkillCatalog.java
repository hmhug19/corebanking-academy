package vn.com.coreacademy.assistant.skill;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads the prompt files once: {@code assistant/style.md}, {@code suggestions.md}, {@code explain.md}, the generation step
 * files and, per skill, {@code system.md}, {@code examples.md} and
 * {@code skill.yml}. {@link #version()} is a hash of every one of those files, stored on each turn so an answer can be
 * traced to the prompt that produced it.
 */
@Component
public class SkillCatalog {
    public static final String DEFAULT = "qa_tai_lieu";
    private static final String ROOT = "assistant/";
    private static final int VERSION_CHARS = 12;
    /** The prompts of course generation, one file per step in {@code skills/tao_khoa_hoc/steps/}. */
    private static final List<String> GENERATION_STEPS = List.of("summarise_sections", "outline", "lesson",
        "questions", "draft_basics", "draft_outline");

    private final String style;
    private final String suggestions;
    private final String explain;
    private final Map<String, String> generationSteps = new LinkedHashMap<>();
    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final String version;

    public SkillCatalog() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MessageDigest digest = digest();
        this.style = read(resolver.getResource("classpath:" + ROOT + "style.md"), digest);
        this.suggestions = read(resolver.getResource("classpath:" + ROOT + "suggestions.md"), digest);
        this.explain = read(resolver.getResource("classpath:" + ROOT + "explain.md"), digest);
        for (String step : GENERATION_STEPS) {
            generationSteps.put(step, read(resolver.getResource(
                "classpath:" + ROOT + "skills/tao_khoa_hoc/steps/" + step + ".md"), digest));
        }
        try {
            List<String> ids = new ArrayList<>();
            for (Resource config : resolver.getResources("classpath*:" + ROOT + "skills/*/skill.yml")) {
                String url = config.getURL().toString();
                String dir = url.substring(0, url.lastIndexOf('/'));
                ids.add(dir.substring(dir.lastIndexOf('/') + 1));
            }
            ids.sort(Comparator.naturalOrder());
            for (String id : ids) {
                skills.put(id, load(resolver, id, digest));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        if (!skills.containsKey(DEFAULT)) {
            throw new IllegalStateException("Missing default skill " + DEFAULT);
        }
        this.version = HexFormat.of().formatHex(digest.digest()).substring(0, VERSION_CHARS);
    }

    /** A fixed catalog, for tests that need skills of their own. */
    public SkillCatalog(String style, List<Skill> list, String version) {
        this.style = style;
        this.suggestions = "";
        this.explain = "";
        list.forEach(skill -> skills.put(skill.id(), skill));
        this.version = version;
    }

    @SuppressWarnings("unchecked")
    private static Skill load(PathMatchingResourcePatternResolver resolver, String id, MessageDigest digest) {
        String dir = "classpath:" + ROOT + "skills/" + id + "/";
        Map<String, Object> yaml = new Yaml().load(read(resolver.getResource(dir + "skill.yml"), digest));
        String system = read(resolver.getResource(dir + "system.md"), digest);
        String examples = read(resolver.getResource(dir + "examples.md"), digest);

        return new Skill(id, String.valueOf(yaml.getOrDefault("description", id)),
            List.copyOf((List<String>) yaml.getOrDefault("tools", List.of())),
            List.copyOf((List<String>) yaml.getOrDefault("roles", List.of())), system, examples,
            String.valueOf(yaml.getOrDefault("output", "")),
            yaml.get("requiredTool") == null ? null : String.valueOf(yaml.get("requiredTool")));
    }

    private static String read(Resource resource, MessageDigest digest) {
        try (InputStream in = resource.getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            digest.update(String.valueOf(resource.getFilename()).getBytes(StandardCharsets.UTF_8));
            digest.update(text.getBytes(StandardCharsets.UTF_8));

            return text.strip();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String version() {
        return version;
    }

    public Skill defaultSkill() {
        return skills.get(DEFAULT);
    }

    public Optional<Skill> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(skills.get(id.strip()));
    }

    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    /**
     * The system prompt of a chat turn: the shared style, the suggestion rule, then the skill's rules, output rule and
     * examples.
     */
    public String systemPrompt(Skill skill) {
        StringBuilder text = new StringBuilder(style);
        if (!suggestions.isBlank()) {
            text.append("\n\n").append(suggestions);
        }
        text.append("\n\n").append(skill.system());
        if (!skill.output().isBlank()) {
            text.append("\n\nĐịnh dạng đầu ra: ").append(skill.output());
        }
        if (!skill.examples().isBlank()) {
            text.append("\n\nVí dụ:\n").append(skill.examples());
        }

        return text.toString();
    }

    /** The system prompt that explains one reviewed quiz question: the shared style, then the explanation rules. */
    public String explainPrompt() {
        return style + "\n\n" + explain;
    }

    /**
     * The system prompt of one course generation step: that step's file and one line fixing the language. Never the
     * chat style or the suggestion rule, which contradict "return one JSON object".
     */
    public String generationPrompt(String step) {
        String rules = generationSteps.get(step);
        if (rules == null) {
            throw new IllegalArgumentException("Unknown generation step " + step);
        }

        return rules + "\n\nViết bằng tiếng Việt.";
    }

    /** The shared style alone, for the grounded document chat. */
    public String style() {
        return style;
    }

    /** The suggestion-marker rule, for the grounded document chat, which also offers follow-up questions. */
    public String suggestions() {
        return suggestions;
    }
}
