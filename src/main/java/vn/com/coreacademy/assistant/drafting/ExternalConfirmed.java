package vn.com.coreacademy.assistant.drafting;

import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vn.com.coreacademy.assistant.drafting.DraftingViews.RulesConfirm;
import vn.com.coreacademy.learning.dto.LearningRequests.ChapterInput;
import vn.com.coreacademy.learning.dto.LearningRequests.CreateCourse;
import vn.com.coreacademy.learning.dto.LearningRequests.DraftContent;
import vn.com.coreacademy.learning.dto.LearningRequests.DraftRequest;

/**
 * What a Trợ lý ngoài reads back of a confirmed step: the listed values the author confirmed, their edits included, and
 * nothing else. Never lesson content, covers or anything the author wrote beyond these fields.
 */
final class ExternalConfirmed {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ExternalConfirmed() {
    }

    /** Step 1: the course code and the basics of its draft. */
    static ObjectNode basics(CreateCourse course) {
        ObjectNode values = NODES.objectNode().put("code", course.code().strip());
        DraftContent draft = course.draft();
        String title = course.title() != null ? course.title() : draft == null ? null : draft.title();
        values.put("title", title);
        if (draft != null) {
            values.put("summary", draft.summary()).put("level", draft.level())
                .put("defaultRequirement", draft.defaultRequirement())
                .put("programId", draft.programId()).put("moduleId", draft.moduleId());
            strings(values.putArray("objectives"), draft.objectives());
        }
        return values;
    }

    /** Step 2: chapter and lesson titles only. */
    static ObjectNode outline(DraftRequest draft) {
        ObjectNode values = NODES.objectNode();
        ArrayNode chapters = values.putArray("chapters");
        for (ChapterInput chapter : draft.chapters()) {
            ObjectNode out = chapters.addObject().put("title", chapter.title());
            ArrayNode lessons = out.putArray("lessons");
            chapter.lessons().forEach(lesson -> lessons.addObject().put("title", lesson.title()));
        }
        return values;
    }

    /** Step 3: bonus points, the competency and the questions the author kept. */
    static ObjectNode rules(RulesConfirm rules) {
        ObjectNode values = NODES.objectNode().put("rewardPoints", rules.draft().rewardPoints())
            .put("competencyId", rules.competencyId());
        ArrayNode questions = values.putArray("questions");
        rules.questions().forEach(question -> {
            ObjectNode out = questions.addObject().put("type", question.type()).put("content", question.content())
                .put("difficulty", question.difficulty());
            ArrayNode options = out.putArray("options");
            question.options().forEach(option -> options.addObject().put("content", option.content())
                .put("correct", option.correct()));
        });
        return values;
    }

    /** Step 4 keeps nothing: assigning and publishing are the author's own actions. */
    static ObjectNode audience() {
        return NODES.objectNode();
    }

    private static void strings(ArrayNode target, List<String> values) {
        if (values != null) {
            values.forEach(target::add);
        }
    }
}
