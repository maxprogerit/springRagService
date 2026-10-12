package com.springrag.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springrag.embedding.EmbeddingClient;
import com.springrag.generation.ChatClient;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Boots the full app against a real pgvector PostgreSQL (one container per JVM, shared by all subclasses).
 * OpenAI is never called: the embedding and chat clients are mocks.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Generous limits so test suites that register many users or ask many questions are not throttled;
// RateLimitApiTest lowers them in its own context.
@TestPropertySource(properties = {"rag.rate-limit.auth-per-minute=100000", "rag.rate-limit.ask-per-minute=100000"})
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg16").asCompatibleSubstituteFor("postgres"));

    static {
        // Many cached Spring contexts share this one database; the default max_connections (100) runs out.
        POSTGRES.withCommand("postgres", "-c", "max_connections=400");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.embeddingDimension", () -> HashingEmbedder.DIMENSION);
        registry.add("rag.embedding.dimension", () -> HashingEmbedder.DIMENSION);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 4);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
        registry.add("rag.jwt.secret", () -> "test-secret-test-secret-test-secret-123456");
        registry.add("rag.openai.api-key", () -> "test-key-not-used");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @MockitoBean
    protected EmbeddingClient embeddingClient;

    @MockitoBean
    protected ChatClient chatClient;

    @BeforeEach
    void stubEmbeddings() {
        when(embeddingClient.embed(anyList())).thenAnswer(inv -> HashingEmbedder.embedAll(inv.getArgument(0)));
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Registers a new user and returns the JWT. */
    protected String newUserToken() throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + uniqueEmail() + "\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isCreated()).andReturn();
        return body(r).get("token").asText();
    }

    protected long createCollection(String token, String name) throws Exception {
        MvcResult r = mvc.perform(post("/api/collections").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return body(r).get("id").asLong();
    }

    protected MvcResult upload(String token, long collectionId, String filename, byte[] content) throws Exception {
        return mvc.perform(multipart("/api/collections/" + collectionId + "/documents")
                .file(new MockMultipartFile("file", filename, "application/octet-stream", content))
                .header("Authorization", "Bearer " + token)).andReturn();
    }

    /** Uploads a text document and waits until processing finished; returns the document id. */
    protected long uploadAndWait(String token, long collectionId, String filename, byte[] content) throws Exception {
        MvcResult r = upload(token, collectionId, filename, content);
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        long id = body(r).get("id").asLong();
        awaitStatus(token, collectionId, id, "READY");
        return id;
    }

    protected String documentStatus(String token, long collectionId, long documentId) throws Exception {
        MvcResult r = mvc.perform(get("/api/collections/" + collectionId + "/documents")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        for (JsonNode doc : body(r)) {
            if (doc.get("id").asLong() == documentId) {
                return doc.get("status").asText();
            }
        }
        return "MISSING";
    }

    protected void awaitStatus(String token, long collectionId, long documentId, String expected) {
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(
                () -> assertThat(documentStatus(token, collectionId, documentId)).isEqualTo(expected));
    }
}
