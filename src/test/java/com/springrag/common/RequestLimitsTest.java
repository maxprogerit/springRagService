package com.springrag.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.springrag.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class RequestLimitsTest extends IntegrationTestBase {

    @Test
    void jsonStringsAreCappedFarBelowJacksonsDefaultOf20MillionCharacters() {
        String huge = "\"" + "a".repeat(JacksonConfig.MAX_JSON_STRING_LENGTH + 1) + "\"";

        assertThatThrownBy(() -> json.readTree(huge)).isInstanceOf(StreamConstraintsException.class);
        assertThat(json.valueToTree("a".repeat(1000)).asText()).hasSize(1000);
    }

    @Test
    void anOversizedRegistrationBodyIsRejectedAsABadRequest() throws Exception {
        String body = "{\"email\":\"a@b.co\",\"password\":\"" + "p".repeat(200_000) + "\"}";

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
