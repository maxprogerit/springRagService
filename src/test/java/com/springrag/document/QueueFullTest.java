package com.springrag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.springrag.ingestion.IngestionService;
import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class QueueFullTest extends IntegrationTestBase {

    @MockitoBean
    IngestionService ingestion;

    @Test
    void whenTheIngestionQueueIsFullTheDocumentIsMarkedFailedImmediately() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Busy");
        doThrow(new TaskRejectedException("queue full")).when(ingestion)
                .ingestAsync(anyLong(), anyLong(), any(byte[].class), anyString());

        var result = upload(token, collection, "a.txt", "some text".getBytes());

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        JsonNode doc = body(result);
        assertThat(doc.get("status").asText()).isEqualTo("FAILED");
        assertThat(doc.get("errorMessage").asText()).contains("busy");
    }
}
