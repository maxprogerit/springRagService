package com.springrag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.springrag.openai.OpenAiException;
import com.springrag.support.IntegrationTestBase;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

class IngestionApiTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    private static byte[] pdf(String... pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    if (!text.isEmpty()) {
                        cs.showText(text);
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void uploadReturns202ThenDocumentBecomesReadyWithChunks() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Docs");

        MvcResult r = upload(token, collection, "notes.md", "# Title\n\nSome useful notes about refunds.".getBytes());

        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        JsonNode created = body(r);
        assertThat(created.get("status").asText()).isIn("PROCESSING", "READY");
        assertThat(created.get("filename").asText()).isEqualTo("notes.md");
        assertThat(created.get("contentType").asText()).isEqualTo("text/markdown");
        long id = created.get("id").asLong();
        awaitStatus(token, collection, id, "READY");

        JsonNode doc = body(mvc.perform(get("/api/collections/" + collection + "/documents")
                .header("Authorization", "Bearer " + token)).andReturn()).get(0);
        assertThat(doc.get("pageCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void pdfKeepsPageNumbersOnChunks() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Pdfs");

        long id = uploadAndWait(token, collection, "contract.pdf", pdf("Termination clause text", "Payment terms text"));

        var pages = jdbc.queryForList("SELECT page_number FROM chunks WHERE document_id = ? ORDER BY chunk_index",
                Integer.class, id);
        assertThat(pages).containsExactly(1, 2);
        assertThat(jdbc.queryForObject("SELECT page_count FROM documents WHERE id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT content_type FROM documents WHERE id = ?", String.class, id))
                .isEqualTo("application/pdf");
    }

    @Test
    void pdfWithoutTextEndsFailedWithAReadableReason() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Scans");

        MvcResult r = upload(token, collection, "scan.pdf", pdf("", ""));
        long id = body(r).get("id").asLong();
        awaitStatus(token, collection, id, "FAILED");

        String error = jdbc.queryForObject("SELECT error_message FROM documents WHERE id = ?", String.class, id);
        assertThat(error).contains("No extractable text");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void embeddingFailureMarksDocumentFailedAndStoresNoPartialChunks() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Failing");
        when(embeddingClient.embed(anyList())).thenThrow(new OpenAiException(429, "OpenAI rate limit or quota exceeded (HTTP 429)."));

        long id = body(upload(token, collection, "a.txt", "some text".getBytes())).get("id").asLong();
        awaitStatus(token, collection, id, "FAILED");

        assertThat(jdbc.queryForObject("SELECT error_message FROM documents WHERE id = ?", String.class, id))
                .contains("rate limit");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void unexpectedErrorsDoNotLeakDetailsIntoErrorMessage() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Boom");
        when(embeddingClient.embed(anyList())).thenThrow(new IllegalStateException("secret internal detail: db password=hunter2"));

        long id = body(upload(token, collection, "a.txt", "some text".getBytes())).get("id").asLong();
        awaitStatus(token, collection, id, "FAILED");

        assertThat(jdbc.queryForObject("SELECT error_message FROM documents WHERE id = ?", String.class, id))
                .doesNotContain("hunter2").contains("Unexpected error");
    }

    @Test
    void contentIsCheckedNotTheClientDeclaredType() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Types");

        // HTML disguised as text file name is fine as text; a binary executable is not.
        assertThat(upload(token, collection, "run.txt", new byte[] {'M', 'Z', 0, 0, 3}).getResponse().getStatus())
                .isEqualTo(415);
        assertThat(upload(token, collection, "tool.exe", "MZ not a document".getBytes()).getResponse().getStatus())
                .isEqualTo(415);
        assertThat(upload(token, collection, "fake.pdf", "not a pdf at all".getBytes()).getResponse().getStatus())
                .isEqualTo(415);
        MvcResult empty = upload(token, collection, "empty.txt", new byte[0]);
        assertThat(empty.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(empty).get("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE collection_id = ?", Integer.class, collection))
                .isZero();
    }

    @Test
    void filenamesAreSanitizedBeforeStorage() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Names");

        MvcResult r = upload(token, collection, "../../<img src=x onerror=alert(1)>.txt", "hello".getBytes());

        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        String name = body(r).get("filename").asText();
        assertThat(name).doesNotContain("<", ">", "/", "..");
    }

    @Test
    void uploadWithoutFilePartIsABadRequest() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "NoFile");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/collections/" + collection + "/documents")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void deleteDocumentRemovesItsChunksAndChecksCollectionMembership() throws Exception {
        String token = newUserToken();
        long a = createCollection(token, "A");
        long b = createCollection(token, "B");
        long id = uploadAndWait(token, a, "a.txt", "content to delete".getBytes());

        // Wrong collection in the path must not delete the document.
        mvc.perform(delete("/api/collections/" + b + "/documents/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE id = ?", Integer.class, id)).isEqualTo(1);

        mvc.perform(delete("/api/collections/" + a + "/documents/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void textWithFormFeedsGetsRealPageNumbers() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Pages");
        String text = "first page text\fsecond page text\fthird page text";

        long id = uploadAndWait(token, collection, "pages.txt", text.getBytes(StandardCharsets.UTF_8));

        assertThat(jdbc.queryForList("SELECT page_number FROM chunks WHERE document_id = ? ORDER BY chunk_index",
                Integer.class, id)).containsExactly(1, 2, 3);
    }

    @Autowired
    com.springrag.ingestion.DocumentSweeper sweeper;

    @Autowired
    com.springrag.common.RagProperties props;

    private long insertProcessing(long collection, String worker, String age) {
        return jdbc.queryForObject("INSERT INTO documents (collection_id, filename, content_type, size_bytes, status, worker, created_at) "
                + "VALUES (?, 'stuck.txt', 'text/plain', 1, 'PROCESSING', ?, now() - ?::interval) RETURNING id",
                Long.class, collection, worker, age);
    }

    @Test
    void thisInstancesLeftoversAreFailedAtStartupButOtherInstancesFreshWorkIsNot() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Crash");
        long mine = insertProcessing(collection, props.ingestion().instanceId(), "10 seconds");
        long otherFresh = insertProcessing(collection, "some-other-instance", "10 seconds");

        sweeper.onStartup();

        assertThat(documentStatus(token, collection, mine)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_message FROM documents WHERE id = ?", String.class, mine))
                .contains("interrupted");
        assertThat(documentStatus(token, collection, otherFresh)).isEqualTo("PROCESSING");
    }

    @Test
    void documentsOlderThanTheProcessingTimeoutAreFailedByThePeriodicSweep() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Stale");
        long stale = insertProcessing(collection, "dead-instance", "2 hours");
        long fresh = insertProcessing(collection, "live-instance", "1 minute");

        sweeper.sweepStale();

        assertThat(documentStatus(token, collection, stale)).isEqualTo("FAILED");
        assertThat(documentStatus(token, collection, fresh)).isEqualTo("PROCESSING");
    }

    @Test
    void statsReflectDocumentsChunksAndFailures() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Stats");
        uploadAndWait(token, collection, "ok.txt", "good content".getBytes());
        long bad = body(upload(token, collection, "scan.pdf", pdf(""))).get("id").asLong();
        awaitStatus(token, collection, bad, "FAILED");

        JsonNode stats = body(mvc.perform(get("/api/collections/" + collection + "/stats")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn());

        assertThat(stats.at("/documents/total").asInt()).isEqualTo(2);
        assertThat(stats.at("/documents/ready").asInt()).isEqualTo(1);
        assertThat(stats.at("/documents/failed").asInt()).isEqualTo(1);
        assertThat(stats.at("/documents/processing").asInt()).isZero();
        assertThat(stats.get("chunks").asInt()).isEqualTo(1);
        assertThat(stats.at("/queries/total").asInt()).isZero();
        assertThat(stats.at("/queries/avgLatencyMs").isMissingNode()).isTrue();
    }
}
