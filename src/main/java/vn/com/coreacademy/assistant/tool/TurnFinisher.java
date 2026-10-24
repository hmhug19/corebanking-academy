package vn.com.coreacademy.assistant.tool;

/** Turns the model's final text into what the {@code done} event carries. */
@FunctionalInterface
public interface TurnFinisher {
    FinishedAnswer finish(String answer);
}
