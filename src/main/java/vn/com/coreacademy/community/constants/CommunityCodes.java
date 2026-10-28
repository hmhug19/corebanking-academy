package vn.com.coreacademy.community.constants;

import java.util.Set;

public final class CommunityCodes {
    public static final String ACTIVE = "active";
    public static final String DRAFT = "draft";
    public static final String PUBLISHED = "published";
    public static final String SUPERSEDED = "superseded";
    public static final String SUBMITTED = "submitted";
    public static final String REJECTED = "rejected";
    public static final String QUESTION = "question";
    public static final String ANSWER = "answer";
    public static final String ARTICLE = "article";
    public static final String ORGANIZATION = "organization";
    public static final String GUILD = "guild";
    public static final String RESTRICTED = "restricted";
    public static final String CLOSED = "closed";
    public static final String ARCHIVED = "archived";
    public static final String HIDDEN = "hidden";
    public static final String READER = "reader";
    public static final String EDITOR = "editor";
    public static final String UNANSWERED = "unanswered";
    public static final String ANSWERED = "answered";
    public static final String SOLVED = "solved";
    public static final Set<String> KINDS = Set.of(QUESTION, ANSWER, ARTICLE, "recording", "wiki", "radar",
        "architecture_review", "lesson_learned", "knowledge_request");
    public static final Set<String> VISIBILITIES = Set.of(ORGANIZATION, GUILD, RESTRICTED);
    public static final Set<String> VOTABLE_KINDS = Set.of(QUESTION, ANSWER, ARTICLE, "recording", "lesson_learned");
    public static final Set<String> REVIEWED_KINDS = Set.of("wiki", "radar");

    private CommunityCodes() {
    }
}
