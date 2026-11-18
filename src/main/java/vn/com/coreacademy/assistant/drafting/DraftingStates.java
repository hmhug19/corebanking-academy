package vn.com.coreacademy.assistant.drafting;

/** The states of a drafting session and of its steps, as stored and as the session view sends them. */
public final class DraftingStates {
    // ---- a step ----
    /** The assistant works on the step (queued when not claimed yet). */
    public static final String PROPOSING = "proposing";
    /** The step holds a proposal the author reviews. */
    public static final String PROPOSED = "proposed";
    /** The author confirmed the step (Chốt bước). */
    public static final String CONFIRMED = "confirmed";
    /** The assistant could not propose the step; the error says why. */
    public static final String FAILED = "failed";
    /** The author fills the step by hand (Tự điền). */
    public static final String MANUAL = "manual";

    // ---- a session ----
    public static final String ACTIVE = "active";
    public static final String FINISHED = "finished";
    public static final String MANUAL_SESSION = "manual";
    public static final String FAILED_SESSION = "failed";

    // ---- who proposes ----
    /** The in-house model proposes each step through the worker. */
    public static final String IN_HOUSE = "assistant";
    /** A Trợ lý ngoài proposes each step through {@code /api/v1/mcp}; the worker never runs for it. */
    public static final String EXTERNAL = "external";

    // ---- the steps, numbered 1 to 4; the editor's step index is {@code step - 1} ----
    /** Thông tin cơ bản: confirming it creates the draft course. */
    public static final int BASICS_STEP = 1;
    /** Nội dung khóa học: chapters and empty lessons. */
    public static final int OUTLINE_STEP = 2;
    /** Bài kiểm tra & điều kiện: reward points, competency and the final exam's questions. */
    public static final int RULES_STEP = 3;
    /** Đối tượng & xuất bản: confirming it ends the flow. */
    public static final int AUDIENCE_STEP = 4;
    public static final int FIRST_STEP = BASICS_STEP;
    public static final int LAST_STEP = AUDIENCE_STEP;

    private static final String[] KINDS = {"draft_basics", "draft_outline", "draft_rules", "draft_audience"};

    private DraftingStates() {
    }

    /** The generation kind (prompt and schema) of a step: {@code draft_basics} for step 1, and so on. */
    public static String kind(int step) {
        if (step < FIRST_STEP || step > LAST_STEP) {
            throw new IllegalArgumentException("No drafting step " + step);
        }
        return KINDS[step - 1];
    }
}
