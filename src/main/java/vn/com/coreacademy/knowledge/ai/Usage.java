package vn.com.coreacademy.knowledge.ai;

/**
 * The token counts Ollama reports on the last line of a chat call: the prompt it evaluated, the answer it wrote, and
 * why it stopped ({@code stop}, {@code length}…). Zeros and null when the call did not report them.
 */
public record Usage(int promptTokens, int answerTokens, String doneReason) {
    public static final Usage NONE = new Usage(0, 0, null);
}
