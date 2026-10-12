package com.springrag.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

class AccountSecurityApiTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";

    @MockitoBean
    MailService mail;

    @BeforeEach
    void mailIsConfigured() {
        when(mail.enabled()).thenReturn(true);
    }

    private ResultActions json(String url, String body, String token) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(request);
    }

    private String register(String email) throws Exception {
        return body(json("/api/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null)
                .andExpect(status().isCreated()).andReturn()).get("token").asText();
    }

    private String login(String email, String password) throws Exception {
        return body(json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null)
                .andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private void expectAuthorized(String token, boolean ok) throws Exception {
        mvc.perform(get("/api/collections").header("Authorization", "Bearer " + token))
                .andExpect(ok ? status().isOk() : status().isUnauthorized());
    }

    private String tokenFromMail(String page, String recipient) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail, timeout(5000)).send(eq(recipient), anyString(), text.capture());
        Matcher m = Pattern.compile("/#/" + page + "/([A-Za-z0-9_-]+)").matcher(text.getValue());
        assertThat(m.find()).as("mail contains a %s link", page).isTrue();
        return m.group(1);
    }

    // ----- token revocation -----

    @Test
    void signOutEverywhereRevokesAllExistingTokensImmediately() throws Exception {
        String email = uniqueEmail();
        String laptop = register(email);
        String phone = login(email, PASSWORD);
        expectAuthorized(laptop, true);
        expectAuthorized(phone, true);

        json("/api/auth/logout-all", "{}", phone).andExpect(status().isNoContent());

        expectAuthorized(laptop, false);
        expectAuthorized(phone, false);
        expectAuthorized(login(email, PASSWORD), true); // signing in again works
    }

    @Test
    void changingThePasswordRevokesOldTokensAndReturnsAWorkingOne() throws Exception {
        String email = uniqueEmail();
        String old = register(email);

        String fresh = body(json("/api/auth/change-password",
                "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"a-brand-new-password\"}", old)
                .andExpect(status().isOk()).andReturn()).get("token").asText();

        expectAuthorized(old, false);
        expectAuthorized(fresh, true);
        login(email, "a-brand-new-password");
        json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingThePasswordNeedsTheCurrentOne() throws Exception {
        String token = register(uniqueEmail());

        json("/api/auth/change-password", "{\"currentPassword\":\"wrong-password-1\",\"newPassword\":\"a-brand-new-password\"}", token)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("The current password is wrong."));
        expectAuthorized(token, true);
    }

    @Test
    void accountEndpointsRequireAToken() throws Exception {
        json("/api/auth/logout-all", "{}", null).andExpect(status().isUnauthorized());
        json("/api/auth/change-password", "{\"currentPassword\":\"x\",\"newPassword\":\"yyyyyyyy\"}", null).andExpect(status().isUnauthorized());
    }

    // ----- lockout -----

    @Test
    void anAccountIsLockedAfterTooManyFailedSignInsEvenWithTheRightPassword() throws Exception {
        String email = uniqueEmail();
        register(email);
        for (int i = 0; i < 5; i++) {
            json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"wrong-password-" + i + "\"}", null)
                    .andExpect(status().isUnauthorized());
        }

        json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Retry-After"));
    }

    @Test
    void unknownAddressesAreThrottledToo_soTheLockDoesNotRevealWhichAccountsExist() throws Exception {
        String email = uniqueEmail();
        for (int i = 0; i < 5; i++) {
            json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"wrong-password-" + i + "\"}", null)
                    .andExpect(status().isUnauthorized());
        }

        json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"wrong-password-x\"}", null)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void aSuccessfulSignInResetsTheFailureCount() throws Exception {
        String email = uniqueEmail();
        register(email);
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 4; i++) {
                json("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"wrong-password-" + i + "\"}", null)
                        .andExpect(status().isUnauthorized());
            }
            login(email, PASSWORD);
        }
    }

    // ----- password reset -----

    @Test
    void resetPasswordByEmailEndToEnd() throws Exception {
        String email = uniqueEmail();
        String old = register(email);

        json("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}", null).andExpect(status().isAccepted());
        String token = tokenFromMail("reset", email);
        json("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"reset-to-this-one\"}", null)
                .andExpect(status().isNoContent());

        expectAuthorized(old, false);
        login(email, "reset-to-this-one");
        // The link works once.
        json("/api/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"and-again-please\"}", null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void forgotPasswordAnswersTheSameForUnknownAddressesAndSendsNothing() throws Exception {
        json("/api/auth/forgot-password", "{\"email\":\"" + uniqueEmail() + "\"}", null).andExpect(status().isAccepted());

        verify(mail, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void aNewResetLinkInvalidatesThePreviousOne() throws Exception {
        String email = uniqueEmail();
        register(email);
        json("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}", null).andExpect(status().isAccepted());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mail, timeout(5000)).send(eq(email), anyString(), text.capture());
        String first = extract(text.getValue(), "reset");
        org.mockito.Mockito.clearInvocations(mail);
        json("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}", null).andExpect(status().isAccepted());
        verify(mail, timeout(5000)).send(eq(email), anyString(), text.capture());
        String second = extract(text.getValue(), "reset");

        json("/api/auth/reset-password", "{\"token\":\"" + first + "\",\"newPassword\":\"reset-to-this-one\"}", null)
                .andExpect(status().isBadRequest());
        json("/api/auth/reset-password", "{\"token\":\"" + second + "\",\"newPassword\":\"reset-to-this-one\"}", null)
                .andExpect(status().isNoContent());
    }

    @Test
    void garbageAndExpiredTokensAreRejected() throws Exception {
        json("/api/auth/reset-password", "{\"token\":\"not-a-real-token\",\"newPassword\":\"reset-to-this-one\"}", null)
                .andExpect(status().isBadRequest());
        json("/api/auth/verify-email", "{\"token\":\"not-a-real-token\"}", null).andExpect(status().isBadRequest());
    }

    @Test
    void tokensAreStoredHashedNotInTheClear() throws Exception {
        String email = uniqueEmail();
        register(email);
        json("/api/auth/forgot-password", "{\"email\":\"" + email + "\"}", null).andExpect(status().isAccepted());
        String token = tokenFromMail("reset", email);

        var stored = jdbc().queryForList("SELECT token_hash FROM auth_tokens", String.class);

        assertThat(stored).doesNotContain(token).contains(AuthTokenService.hash(token));
    }

    @Test
    void featureAdvertisedToTheUiMatchesMailConfiguration() throws Exception {
        mvc.perform(get("/api/auth/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordResetAvailable").value(true))
                .andExpect(jsonPath("$.emailVerificationRequired").value(false))
                .andExpect(jsonPath("$.ocrEnabled").value(false))
                .andExpect(jsonPath("$.maxUploadMb").value(20));

        when(mail.enabled()).thenReturn(false);
        mvc.perform(get("/api/auth/config")).andExpect(jsonPath("$.passwordResetAvailable").value(false));
        json("/api/auth/forgot-password", "{\"email\":\"" + uniqueEmail() + "\"}", null)
                .andExpect(status().isNotImplemented()).andExpect(jsonPath("$.code").value("NOT_AVAILABLE"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private org.springframework.jdbc.core.JdbcTemplate jdbc() {
        return jdbc;
    }

    private static String extract(String text, String page) {
        Matcher m = Pattern.compile("/#/" + page + "/([A-Za-z0-9_-]+)").matcher(text);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }
}
