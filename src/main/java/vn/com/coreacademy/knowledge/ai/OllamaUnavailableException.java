package vn.com.coreacademy.knowledge.ai;

/** Ollama is not reachable (connection refused or timeout); the caller may retry later. */
public class OllamaUnavailableException extends RuntimeException {
    public OllamaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
