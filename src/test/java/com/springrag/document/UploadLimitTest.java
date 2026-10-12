package com.springrag.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.springrag.support.IntegrationTestBase;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * The size limit is enforced by the servlet container while parsing the multipart body, which MockMvc bypasses,
 * so this test talks to a real server on a random port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "rag.max-upload-mb=1")
class UploadLimitTest extends IntegrationTestBase {

    @Autowired
    TestRestTemplate rest;

    private ResponseEntity<String> post(String token, long collectionId, int bytes) {
        byte[] data = new byte[bytes];
        Arrays.fill(data, (byte) 'a');
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return "big.txt";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        return rest.exchange("/api/collections/" + collectionId + "/documents", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void filesOverTheLimitAreRejectedWith413InTheStandardFormat() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Limit");

        ResponseEntity<String> response = post(token, collection, 1024 * 1024 + 4096);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(json.readTree(response.getBody()).get("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(json.readTree(response.getBody()).get("status").asInt()).isEqualTo(413);
    }

    @Test
    void aFileJustUnderTheLimitIsAcceptedDespiteTheMultipartEnvelope() throws Exception {
        String token = newUserToken();
        long collection = createCollection(token, "Limit ok");

        ResponseEntity<String> response = post(token, collection, 1024 * 1024 - 1024);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
}
