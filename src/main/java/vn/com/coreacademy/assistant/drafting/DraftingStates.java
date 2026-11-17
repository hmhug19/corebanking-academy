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

    /** Step numbers, 1 to 4; the editor's step index is {@code step - 1}. */
    public static final int FIRST_STEP = 1;
    public static final int LAST_STEP = 4;

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
