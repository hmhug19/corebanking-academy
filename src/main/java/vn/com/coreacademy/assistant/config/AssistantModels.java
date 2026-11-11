package vn.com.coreacademy.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Options;

/**
 * Chat model, temperature and context size per use. Anything left unset keeps the built-in value (and the model
 * of {@code app.knowledge.ollama.chat-model}), so an empty section changes nothing.
 */
@ConfigurationProperties("app.assistant.models")
public record AssistantModels(Use agent, Use grounded, Use classifier, Use generation) {
    private static final Options CLASSIFIER = new Options(0.0, 2048);
    private static final Options GENERATION = new Options(0.4, 8192);

    public record Use(String chatModel, Double temperature, Integer numCtx) {
        Options over(Options base) {
            return new Options(temperature == null ? base.temperature() : temperature,
                numCtx == null ? base.numCtx() : numCtx,
                chatModel == null || chatModel.isBlank() ? base.model() : chatModel.strip());
        }
    }

    public static AssistantModels defaults() {
        return new AssistantModels(null, null, null, null);
    }

    public Options agentOptions() {
        return agent == null ? Options.AGENT : agent.over(Options.AGENT);
    }

    public Options groundedOptions() {
        return grounded == null ? Options.GROUNDED : grounded.over(Options.GROUNDED);
    }

    public Options classifierOptions() {
        return classifier == null ? CLASSIFIER : classifier.over(CLASSIFIER);
    }

    public Options generationOptions() {
        return generation == null ? GENERATION : generation.over(GENERATION);
    }
}
