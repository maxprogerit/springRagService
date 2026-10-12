package com.springrag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.springrag.common.RagProperties;
import com.springrag.openai.OpenAiException;
import com.springrag.openai.OpenAiHttp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final OpenAiHttp http;
    private final String model;
    private final int dimension;
    private final int batchSize;

    public OpenAiEmbeddingClient(OpenAiHttp http, RagProperties props) {
        this.http = http;
        this.model = props.openai().embeddingModel();
        this.dimension = props.embedding().dimension();
        this.batchSize = props.embedding().batchSize();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> all = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            all.addAll(embedBatch(texts.subList(from, Math.min(from + batchSize, texts.size()))));
        }
        return all;
    }

    private List<float[]> embedBatch(List<String> batch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", batch);
        body.put("encoding_format", "float");
        // Only the text-embedding-3 family accepts "dimensions"; it lets one model fit the configured column size.
        if (model.startsWith("text-embedding-3")) {
            body.put("dimensions", dimension);
        }
        JsonNode data = http.post("/embeddings", body).path("data");
        if (!data.isArray() || data.size() != batch.size()) {
            throw new OpenAiException(0, "OpenAI returned an unexpected number of embeddings.");
        }
        float[][] ordered = new float[batch.size()][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode values = item.path("embedding");
            if (index < 0 || index >= ordered.length || ordered[index] != null || values.size() != dimension) {
                throw new OpenAiException(0, "OpenAI returned an embedding of unexpected size (expected "
                        + dimension + "). Check EMBEDDING_DIMENSION and OPENAI_EMBEDDING_MODEL.");
            }
            float[] vector = new float[dimension];
            for (int i = 0; i < dimension; i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            ordered[index] = vector;
        }
        return List.of(ordered);
    }
}
