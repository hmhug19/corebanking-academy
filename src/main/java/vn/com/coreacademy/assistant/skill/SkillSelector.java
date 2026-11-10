package vn.com.coreacademy.assistant.skill;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

/**
 * Picks the skill of a turn. A skill the client names (and the user may use) is taken as it is, with no model call;
 * otherwise one short classifier call chooses, and anything unexpected falls back to the default skill.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillSelector {
    private static final Pattern LABEL = Pattern.compile("[a-z_]+");

    private final OllamaClient ollama;
    private final SkillCatalog catalog;
    private final AssistantModels models;

    public Skill select(String requested, String question, AgentContext context) {
        Skill named = catalog.find(requested).filter(skill -> offered(skill, context)).orElse(null);
        if (named != null) {
            return named;
        }
        if (context.lesson() != null) {
            // Asked from a lesson page: the question is about that lesson, so no classifier call is needed.
            return catalog.defaultSkill();
        }

        List<Skill> allowed = catalog.all().stream().filter(skill -> offered(skill, context)).toList();
        try {
            String reply = ollama.chat(List.of(
                OllamaClient.Message.system(prompt(allowed)), OllamaClient.Message.user(question)),
                models.classifierOptions());
            if (reply != null) {
                Matcher matcher = LABEL.matcher(reply.toLowerCase());
                while (matcher.find()) {
                    String label = matcher.group();
                    for (Skill skill : allowed) {
                        if (skill.id().equals(label)) {
                            return skill;
                        }
                    }
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Skill classifier failed type={}", exception.getClass().getName());
        }

        return catalog.defaultSkill();
    }

    /** The user's role allows it, and a skill that reads the open lesson needs a lesson to be open. */
    private static boolean offered(Skill skill, AgentContext context) {
        return skill.allowedFor(context) && (!skill.needsLesson() || context.lesson() != null);
    }

    private static String prompt(List<Skill> allowed) {
        StringBuilder text = new StringBuilder("Chọn đúng một nhãn kỹ năng phù hợp nhất với yêu cầu của người dùng. "
            + "Chỉ trả lời nhãn, không giải thích.\n");
        for (Skill skill : allowed) {
            text.append("- ").append(skill.id()).append(": ").append(skill.description()).append('\n');
        }

        return text.toString();
    }
}
