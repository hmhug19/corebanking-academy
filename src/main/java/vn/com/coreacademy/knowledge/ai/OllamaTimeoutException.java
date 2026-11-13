package vn.com.coreacademy.knowledge.ai;

/**
 * Ollama accepted the call but did not answer within the read timeout. It is still an
 * {@link OllamaUnavailableException} for callers that only retry later; course generation counts it as a failed
 * attempt of the step instead, because a call that is too slow once is usually too slow again.
 */
public class OllamaTimeoutException extends OllamaUnavailableException {
    public OllamaTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
