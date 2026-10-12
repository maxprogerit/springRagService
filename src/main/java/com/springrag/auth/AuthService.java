package com.springrag.auth;

import com.springrag.common.ApiException;
import com.springrag.common.RagProperties;
import java.util.Locale;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final LoginAttemptTracker attempts;
    private final AuthTokenService tokens;
    private final MailService mail;
    private final Executor mailExecutor;
    private final RagProperties props;
    /** Verified against when the email is unknown, so unknown users and wrong passwords cost the same time. */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, LoginAttemptTracker attempts,
                       AuthTokenService tokens, MailService mail, @Qualifier("mailExecutor") Executor mailExecutor,
                       RagProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.attempts = attempts;
        this.tokens = tokens;
        this.mail = mail;
        this.mailExecutor = mailExecutor;
        this.props = props;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    public AuthResponse register(String email, String password) {
        String normalized = normalize(email);
        if (users.existsByEmail(normalized)) {
            throw ApiException.conflict("Email is already registered.");
        }
        boolean verify = props.auth().requireEmailVerification();
        User user = new User(normalized, encoder.encode(password));
        user.setEmailVerified(!verify);
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a parallel registration of the same email.
            throw ApiException.conflict("Email is already registered.");
        }
        if (verify) {
            sendVerificationMail(user);
            return new AuthResponse(null, user.getEmail(), true);
        }
        return AuthResponse.signedIn(jwt.issue(user), user.getEmail());
    }

    public AuthResponse login(String email, String password) {
        String normalized = normalize(email);
        attempts.checkNotLocked(normalized);
        User user = users.findByEmail(normalized).orElse(null);
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean matches = encoder.matches(password, hash);
        if (user == null || !matches) {
            attempts.recordFailure(normalized);
            throw ApiException.unauthorized("Invalid email or password.");
        }
        if (!user.isEmailVerified()) {
            throw ApiException.forbidden("EMAIL_NOT_VERIFIED",
                    "Confirm your e-mail address first: use the link in the message we sent you.");
        }
        attempts.clear(normalized);
        return AuthResponse.signedIn(jwt.issue(user), user.getEmail());
    }

    /** Signs the user out of every device: all tokens issued so far stop working. */
    @Transactional
    public void logoutEverywhere(long userId) {
        users.findById(userId).ifPresent(User::revokeAllTokens);
    }

    /** Changes the password, revokes all existing tokens and returns a fresh one for the current device. */
    @Transactional
    public AuthResponse changePassword(long userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Account not found."));
        String normalized = user.getEmail();
        attempts.checkNotLocked(normalized);
        if (!encoder.matches(currentPassword, user.getPasswordHash())) {
            attempts.recordFailure(normalized);
            throw ApiException.badRequest("The current password is wrong.");
        }
        user.changePassword(encoder.encode(newPassword));
        users.saveAndFlush(user);
        attempts.clear(normalized);
        return AuthResponse.signedIn(jwt.issue(user), user.getEmail());
    }

    public void forgotPassword(String email) {
        requireMail();
        users.findByEmail(normalize(email)).ifPresent(user -> {
            String token = tokens.create(user.getId(), AuthTokenService.Type.RESET_PASSWORD);
            sendAsync(user.getEmail(), "Reset your password",
                    "Someone asked to reset the password of this account.\n\nOpen this link within 30 minutes to choose a new password:\n"
                            + link("reset", token) + "\n\nIf this was not you, ignore this message: nothing changes.");
        });
        // Always the same outcome, whether or not the address is registered.
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        long userId = tokens.consume(token, AuthTokenService.Type.RESET_PASSWORD)
                .orElseThrow(() -> ApiException.badRequest("This reset link is invalid or has expired."));
        User user = users.findById(userId).orElseThrow(() -> ApiException.badRequest("This reset link is invalid or has expired."));
        user.changePassword(encoder.encode(newPassword));
        user.setEmailVerified(true); // following the e-mailed link proves control of the address
        users.saveAndFlush(user);
        attempts.clear(user.getEmail());
    }

    @Transactional
    public void verifyEmail(String token) {
        long userId = tokens.consume(token, AuthTokenService.Type.VERIFY_EMAIL)
                .orElseThrow(() -> ApiException.badRequest("This verification link is invalid or has expired."));
        users.findById(userId).ifPresent(user -> user.setEmailVerified(true));
    }

    public void resendVerification(String email) {
        requireMail();
        users.findByEmail(normalize(email)).filter(user -> !user.isEmailVerified()).ifPresent(this::sendVerificationMail);
    }

    public boolean passwordResetAvailable() {
        return mail.enabled();
    }

    private void sendVerificationMail(User user) {
        String token = tokens.create(user.getId(), AuthTokenService.Type.VERIFY_EMAIL);
        sendAsync(user.getEmail(), "Confirm your e-mail address",
                "Welcome! Open this link to confirm your e-mail address:\n" + link("verify", token)
                        + "\n\nThe link works for 24 hours.");
    }

    /** Mail is sent on a separate thread so response time does not reveal whether an address is registered. */
    private void sendAsync(String to, String subject, String text) {
        mailExecutor.execute(() -> {
            try {
                mail.send(to, subject, text);
            } catch (RuntimeException e) {
                log.error("Could not send e-mail '{}': {}", subject, e.getMessage());
            }
        });
    }

    private String link(String page, String token) {
        String base = props.auth().baseUrl();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/#/" + page + "/" + token;
    }

    private void requireMail() {
        if (!mail.enabled()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "NOT_AVAILABLE",
                    "E-mail is not configured on this server, so this feature is unavailable.");
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
