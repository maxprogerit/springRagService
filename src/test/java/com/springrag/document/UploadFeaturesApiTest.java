package com.springrag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.springrag.support.Docs;
import com.springrag.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class UploadFeaturesApiTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void wordDocumentsAreIngestedWithPageNumbersFromPageBreaks() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Word");

        long id = uploadAndWait(token, collection, "memo.docx", Docs.docx("Budget approved in March", "Hiring freeze starts in June"));

        assertThat(jdbc.queryForObject("SELECT content_type FROM documents WHERE id = ?", String.class, id))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(jdbc.queryForList("SELECT page_number FROM chunks WHERE document_id = ? ORDER BY chunk_index",
                Integer.class, id)).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT text FROM chunks WHERE document_id = ? AND page_number = 2", String.class, id))
                .contains("Hiring freeze");
    }

    @Test
    void htmlPagesAreIngestedAsPlainText() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Html");
        String html = "<html><head><title>Policy</title><script>steal()</script></head><body><h1>Refunds</h1>"
                + "<p>Refunds are paid within 14 days.</p></body></html>";

        long id = uploadAndWait(token, collection, "policy.html", html.getBytes(StandardCharsets.UTF_8));

        String text = jdbc.queryForObject("SELECT string_agg(text, ' ') FROM chunks WHERE document_id = ?", String.class, id);
        assertThat(text).contains("Refunds are paid within 14 days").doesNotContain("steal").doesNotContain("<");
    }

    @Test
    void utf16TextFilesAreAccepted() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Utf16");
        byte[] body = "Привет, это тест".getBytes(StandardCharsets.UTF_16LE);
        byte[] data = new byte[body.length + 2];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xFE;
        System.arraycopy(body, 0, data, 2, body.length);

        long id = uploadAndWait(token, collection, "notepad.txt", data);

        assertThat(jdbc.queryForObject("SELECT text FROM chunks WHERE document_id = ?", String.class, id)).isEqualTo("Привет, это тест");
    }

    @Test
    void imagesAreRefusedWhileOcrIsDisabled() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Img");

        MvcResult r = upload(token, collection, "scan.png", Docs.png("Hello"));

        assertThat(r.getResponse().getStatus()).isEqualTo(415);
        assertThat(body(r).get("message").asText()).contains("OCR");
    }

    @Test
    void theSameFileTwiceInOneCollectionIsRejectedButAllowedElsewhere() throws Exception {
        String token = newUserToken();
        long first = createCollection(token, "First");
        long second = createCollection(token, "Second");
        byte[] data = "identical bytes in two uploads".getBytes();
        uploadAndWait(token, first, "original.txt", data);

        MvcResult again = upload(token, first, "renamed-copy.txt", data);

        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(again).get("code").asText()).isEqualTo("DUPLICATE_DOCUMENT");
        assertThat(body(again).get("message").asText()).contains("original.txt");
        assertThat(upload(token, second, "original.txt", data).getResponse().getStatus()).isEqualTo(202);
    }

    @Test
    void aFailedEarlierAttemptIsReplacedByTheNewUpload() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Retry");
        byte[] data = "content that fails once".getBytes();
        org.mockito.Mockito.doThrow(new com.springrag.openai.OpenAiException(503, "OpenAI server error (HTTP 503)."))
                .when(embeddingClient).embed(org.mockito.ArgumentMatchers.anyList());
        long failed = body(upload(token, collection, "a.txt", data)).get("id").asLong();
        awaitStatus(token, collection, failed, "FAILED");
        org.mockito.Mockito.doAnswer(inv -> com.springrag.support.HashingEmbedder.embedAll(inv.getArgument(0)))
                .when(embeddingClient).embed(org.mockito.ArgumentMatchers.anyList());

        MvcResult retry = upload(token, collection, "a.txt", data);

        assertThat(retry.getResponse().getStatus()).isEqualTo(202);
        long retried = body(retry).get("id").asLong();
        awaitStatus(token, collection, retried, "READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE id = ?", Integer.class, failed)).isZero();
    }

    @Test
    void listsArePagedWithTheTotalInAHeader() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Paged");
        for (int i = 0; i < 5; i++) {
            uploadAndWait(token, collection, "doc" + i + ".txt", ("content number " + i).getBytes());
        }
        for (int i = 0; i < 3; i++) {
            createCollection(token, "extra " + i);
        }
        String auth = "Bearer " + token;

        MvcResult docs = mvc.perform(get("/api/collections/" + collection + "/documents?page=1&size=2").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(header().string("X-Total-Count", "5")).andReturn();
        MvcResult collections = mvc.perform(get("/api/collections?page=0&size=2").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(header().string("X-Total-Count", "4")).andReturn();

        JsonNode page = body(docs);
        assertThat(page).hasSize(2);
        assertThat(page.get(0).get("filename").asText()).isEqualTo("doc2.txt"); // newest first: 4,3 | 2,1 | 0
        assertThat(page.get(1).get("filename").asText()).isEqualTo("doc1.txt");
        assertThat(body(collections)).hasSize(2);
    }

    @Test
    void pageSizeIsClampedAndGarbageParametersAreHandled() throws Exception {
        String token = newUserToken();
        createCollection(token, "One");
        String auth = "Bearer " + token;

        mvc.perform(get("/api/collections?size=100000&page=-4").header("Authorization", auth))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/collections?size=abc").header("Authorization", auth))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void searchHonoursTheSameOwnershipRulesForNewFormats() throws Exception {
        String alice = newUserToken();
        String bob = newUserToken();
        long ac = createCollection(alice, "Private word docs");
        uploadAndWait(alice, ac, "secret.docx", Docs.docx("Project Zanzibar launch date is classified"));

        mvc.perform(post("/api/collections/" + ac + "/search").header("Authorization", "Bearer " + bob)
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"Zanzibar\"}")).andExpect(status().isNotFound());
    }
}
