package com.springrag.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.springrag.support.IntegrationTestBase;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(properties = {"rag.auth.require-email-verification=true", "rag.mail.host=smtp.test.invalid"})
class EmailVerificationApiTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";

    @MockitoBean
    MailService mail;

    @BeforeEach
    void mailIsConfigured() {
        when(mail.enabled()).thenReturn(true);
    }

    private ResultActions json(String url, String body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String verificationToken(String email) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail, timeout(5000)).send(eq(email), anyString(), text.capture());
        Matcher m = Pattern.compile("/#/verify/([A-Za-z0-9_-]+)").matcher(text.getValue());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void registrationSendsALinkAndSignInIsBlockedUntilItIsUsed() throws Exception {
        String email = uniqueEmail();

        var response = body(json("/api/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isCreated()).andReturn());
        assertThat(response.has("token")).isFalse();
        assertThat(response.get("verificationRequired").asBoolean()).isTrue();

        json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));

        String token = verificationToken(email);
        json("/api/auth/verify-email", "{\"token\":\"" + token + "\"}").andExpect(status().isNoContent());

        String jwt = body(json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isOk()).andReturn()).get("token").asText();
        mvc.perform(get("/api/collections").header("Authorization", "Bearer " + jwt)).andExpect(status().isOk());
        // The link is single-use.
        json("/api/auth/verify-email", "{\"token\":\"" + token + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void wrongPasswordStillLooksLikeAnyOtherFailureForUnverifiedAccounts() throws Exception {
        String email = uniqueEmail();
        json("/api/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").andExpect(status().isCreated());

        json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"wrong-password-1\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theVerificationLinkCanBeSentAgain() throws Exception {
        String email = uniqueEmail();
        json("/api/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").andExpect(status().isCreated());
        String first = verificationToken(email);
        org.mockito.Mockito.clearInvocations(mail);

        json("/api/auth/resend-verification", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
        String second = verificationToken(email);

        assertThat(second).isNotEqualTo(first);
        json("/api/auth/verify-email", "{\"token\":\"" + first + "\"}").andExpect(status().isBadRequest());
        json("/api/auth/verify-email", "{\"token\":\"" + second + "\"}").andExpect(status().isNoContent());
    }

    @Test
    void resettingThePasswordAlsoProvesTheAddress() throws Exception {
        String email = uniqueEmail();
        json("/api/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").andExpect(status().isCreated());
        org.mockito.Mockito.clearInvocations(mail);
        json("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}").andExpect(status().isAccepted());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail, timeout(5000)).send(eq(email), anyString(), text.capture());
        Matcher m = Pattern.compile("/#/reset/([A-Za-z0-9_-]+)").matcher(text.getValue());
        assertThat(m.find()).isTrue();

        json("/api/auth/reset-password", "{\"token\":\"" + m.group(1) + "\",\"newPassword\":\"reset-to-this-one\"}")
                .andExpect(status().isNoContent());

        json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"reset-to-this-one\"}").andExpect(status().isOk());
    }

    @Test
    void theUiIsToldThatVerificationIsRequired() throws Exception {
        mvc.perform(get("/api/auth/config")).andExpect(jsonPath("$.emailVerificationRequired").value(true));
    }
}
