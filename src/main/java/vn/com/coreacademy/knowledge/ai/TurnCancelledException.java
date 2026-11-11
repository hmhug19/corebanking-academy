package vn.com.coreacademy.knowledge.ai;

/** The user stopped the turn while the model was answering. */
public class TurnCancelledException extends RuntimeException {
    public TurnCancelledException() {
        super("The turn was cancelled");
    }
}
