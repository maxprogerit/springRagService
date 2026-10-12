package com.springrag.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class AskResilienceTest extends IntegrationTestBase {

    @MockitoBean
    QueryLog queryLog;

    @Test
    void aFailingAuditLogDoesNotLoseAnAlreadyComputedAnswer() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Audit");
        uploadAndWait(token, collection, "faq.txt", "Refunds are possible within 14 days.".getBytes());
        when(chatClient.complete(anyString(), anyString())).thenReturn("Refunds take 14 days [1].");
        doThrow(new IllegalStateException("queries_log is unavailable"))
                .when(queryLog).record(anyLong(), anyLong(), anyString(), anyInt(), anyLong(), anyList(), anyBoolean());

        var result = mvc.perform(post("/api/collections/" + collection + "/ask").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"refunds days\"}"))
                .andExpect(status().isOk()).andReturn();

        assertThat(body(result).get("citations")).hasSize(1);
    }
}
