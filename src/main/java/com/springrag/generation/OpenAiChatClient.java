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
        this.temperature = props.openai().chatTemperature();
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
