package vn.com.coreacademy.assistant.metrics;

import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

/** Sends the duration of every embedding call to the assistant's metrics. */
@Component
public class EmbedTiming {
    public EmbedTiming(OllamaClient ollama, AssistantMetrics metrics) {
        ollama.onEmbed(metrics::embed);
    }
}
