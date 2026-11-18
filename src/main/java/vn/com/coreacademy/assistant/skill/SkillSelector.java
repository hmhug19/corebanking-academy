package vn.com.coreacademy.assistant.skill;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.conversation.Conversations;
import vn.com.coreacademy.identity.dto.FunctionInfo;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

/**
 * Picks the skill of a turn. A skill the client names (and the user may use) is taken as it is, with no model call;
 * otherwise one short classifier call chooses, and anything unexpected falls back to the default skill.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillSelector {
    public static final String LESSON_SKILL = "hoi_bai_hoc";
    public static final String NAVIGATION_SKILL = "dieu_huong";
    /** "Mở trang …", "vào màn hình …": a plain request to open a screen, never a question about its data. */
    private static final Pattern OPEN_PAGE = Pattern.compile(
        "^(mở|vào|đi tới|đi đến|chuyển tới|chuyển đến|truy cập)\\s+(lại\\s+)?(trang|màn hình|mục)(?!\\s*\\d)(\\s|$)",
        Pattern.UNICODE_CASE | Pattern.CASE_INSENSITIVE);
    private static final Pattern LABEL = Pattern.compile("[a-z_]+");

    private final OllamaClient ollama;
    private final SkillCatalog catalog;
    private final AssistantModels models;

    /** The last exchange is cut to this many characters of answer; the classifier needs the topic, not the text. */
    public static final int ANSWER_CUT = 300;

    public Skill select(String requested, String question, AgentContext context) {
        return select(requested, question, context, null);
    }

    /**
     * {@code last} is the previous exchange of the conversation (null for a first question): a follow-up such as
     * "còn trường hợp kia?" is classified with the topic of the turn before it.
     */
    public Skill select(String requested, String question, AgentContext context, Conversations.Exchange last) {
        Skill named = catalog.find(requested).filter(skill -> offered(skill, context)).orElse(null);
        if (named != null) {
            return named;
        }
        Skill navigation = catalog.find(NAVIGATION_SKILL).filter(skill -> offered(skill, context)).orElse(null);
        if (navigation != null && question != null && OPEN_PAGE.matcher(question.strip()).find()) {
            // The wording alone says "open a screen": no model call, and no chance of a document search instead.
            return navigation;
        }
        if (context.lesson() != null) {
            // Asked from a lesson page: the question is about that lesson, so no classifier call is needed; the
            // lesson skill reads the lesson itself first.
            return catalog.find(LESSON_SKILL).orElse(catalog.defaultSkill());
        }

        List<Skill> allowed = catalog.all().stream().filter(skill -> offered(skill, context)).toList();
        try {
            String reply = ollama.chat(List.of(
                OllamaClient.Message.system(prompt(allowed, context)),
                OllamaClient.Message.user(classifierInput(question, last))),
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

    static String classifierInput(String question, Conversations.Exchange last) {
        if (last == null || last.question() == null) {
            return question;
        }
        String answer = last.answer() == null ? "" : last.answer().replaceAll("\\s+", " ").strip();
        if (answer.length() > ANSWER_CUT) {
            answer = answer.substring(0, ANSWER_CUT) + "…";
        }

        return "Lượt trước, người dùng hỏi: " + last.question().replaceAll("\\s+", " ").strip()
            + "\nTrợ lý trả lời: " + answer + "\nCâu hỏi mới (chọn kỹ năng cho câu này, dựa vào lượt trước nếu câu "
            + "mới là hỏi tiếp): " + question;
    }

    /** The user's role allows it, and a skill that reads the open lesson needs a lesson to be open. */
    private static boolean offered(Skill skill, AgentContext context) {
        return skill.allowedFor(context) && (!skill.needsLesson() || context.lesson() != null);
    }

    private static String prompt(List<Skill> allowed, AgentContext context) {
        StringBuilder text = new StringBuilder("Chọn đúng một nhãn kỹ năng phù hợp nhất với yêu cầu của người dùng. "
            + "Chỉ trả lời nhãn, không giải thích.\n");
        for (Skill skill : allowed) {
            text.append("- ").append(skill.id()).append(": ").append(skill.description());
            if (NAVIGATION_SKILL.equals(skill.id())) {
                text.append(screenNames(context));
            }
            text.append('\n');
        }

        return text.toString();
    }

    /** The names of the screens this user can open, so an "open the page of a screen name" request is recognised as navigation. */
    static String screenNames(AgentContext context) {
        if (context.rights() == null || context.rights().functions() == null) {
            return "";
        }
        String names = context.rights().functions().stream()
            .filter(function -> function.routePattern() != null && !function.routePattern().isBlank()
                && !function.routePattern().contains(":"))
            .map(FunctionInfo::name).distinct().collect(Collectors.joining(", "));

        return names.isEmpty() ? "" : ". Các màn hình của người dùng: " + names;
    }
}
