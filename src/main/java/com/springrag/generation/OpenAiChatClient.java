package com.springrag.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.springrag.common.RagProperties;
import com.springrag.openai.OpenAiException;
import com.springrag.openai.OpenAiHttp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class OpenAiChatClient implements ChatClient {

    private final OpenAiHttp http;
    private final String model;
    private final Double temperature;

    public OpenAiChatClient(OpenAiHttp http, RagProperties props) {
        this.http = http;
        this.model = props.openai().chatModel();
        this.temperature = temperatureFor(model, props.openai().chatTemperature());
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        JsonNode content = http.post("/chat/completions", body(systemPrompt, userPrompt, false))
                .path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new OpenAiException(0, "OpenAI returned no answer text.");
        }
        return content.asText();
    }

    @Override
    public String stream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        StringBuilder full = new StringBuilder();
        http.postStream("/chat/completions", body(systemPrompt, userPrompt, true), event -> {
            JsonNode delta = event.path("choices").path(0).path("delta").path("content");
            if (delta.isTextual() && !delta.asText().isEmpty()) {
                full.append(delta.asText());
                onDelta.accept(delta.asText());
            }
        });
        if (full.isEmpty()) {
            throw new OpenAiException(0, "OpenAI returned no answer text.");
        }
        return full.toString();
    }

    /**
     * Grounded answers should be as deterministic as possible, hence 0. Reasoning models (gpt-5 family, o-series)
     * reject any temperature other than their default, so nothing is sent for them unless one is configured explicitly.
     */
    static Double temperatureFor(String model, Double configured) {
        if (configured != null) {
            return configured;
        }
        String name = model.toLowerCase(java.util.Locale.ROOT);
        boolean reasoning = name.startsWith("gpt-5") || name.matches("o[0-9].*");
        return reasoning ? null : 0.0;
    }

    private Map<String, Object> body(String systemPrompt, String userPrompt, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (stream) {
            body.put("stream", true);
        }
        return body;
    }
}
