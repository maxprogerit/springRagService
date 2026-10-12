package com.springrag.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Framework-level errors must use the same { timestamp, status, code, message, path } body as our own. */
class ErrorFormatTest extends IntegrationTestBase {

    @Test
    void wrongContentTypeIs415NotA500() throws Exception {
        String token = newUserToken();

        mvc.perform(post("/api/collections").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.TEXT_PLAIN).content("name=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.path").value("/api/collections"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void wrongMethodIs405() throws Exception {
        String token = newUserToken();

        mvc.perform(get("/api/collections/1/ask").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unknownApiPathIs404InStandardFormat() throws Exception {
        String token = newUserToken();

        mvc.perform(get("/api/nope").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/nope"));
    }

    @Test
    void missingJsonBodyIs400() throws Exception {
        String token = newUserToken();

        mvc.perform(post("/api/collections").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
