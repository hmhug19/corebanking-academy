package vn.com.coreacademy.knowledge.ai;

/** Ollama answered, but not with what was asked for (non-2xx status, an error line or an unexpected shape). */
public class OllamaException extends RuntimeException {
    public OllamaException(String message) {
        super(message);
    }

    public OllamaException(String message, Throwable cause) {
        super(message, cause);
    }
}
