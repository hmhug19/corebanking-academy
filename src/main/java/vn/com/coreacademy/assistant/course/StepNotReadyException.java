package vn.com.coreacademy.assistant.course;

/** The step cannot run yet (the source document is still being read); the worker tries it again later. */
public class StepNotReadyException extends RuntimeException {
    public StepNotReadyException(String message) {
        super(message);
    }
}
