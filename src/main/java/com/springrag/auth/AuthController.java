package com.springrag.auth;

import static com.springrag.auth.CurrentUser.id;

import com.springrag.common.RagProperties;
import com.springrag.common.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    // bcrypt only uses the first 72 bytes of a password, so longer input would silently be truncated.
    private static final String PASSWORD_RULE = "must be 8 to 72 characters";

    public record Credentials(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72, message = PASSWORD_RULE) String password) {
        @Override
        public String toString() {
            return "Credentials[email=" + email + ", password=<redacted>]";
        }
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record TokenRequest(@NotBlank @Size(max = 200) String token) {
    }

    public record ResetRequest(@NotBlank @Size(max = 200) String token,
                               @NotBlank @Size(min = 8, max = 72, message = PASSWORD_RULE) String newPassword) {
        @Override
        public String toString() {
            return "ResetRequest[token=<redacted>, newPassword=<redacted>]";
        }
    }

    public record ChangePasswordRequest(@NotBlank @Size(max = 72) String currentPassword,
                                        @NotBlank @Size(min = 8, max = 72, message = PASSWORD_RULE) String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordRequest[<redacted>]";
        }
    }

    /** What the sign-in page and upload form need to know before the user is signed in. */
    public record PublicConfig(boolean passwordResetAvailable, boolean emailVerificationRequired, boolean ocrEnabled,
                               int maxUploadMb) {
    }

    private final AuthService auth;
    private final RateLimiter limiter;
    private final RagProperties props;

    public AuthController(AuthService auth, RateLimiter limiter, RagProperties props) {
        this.auth = auth;
        this.limiter = limiter;
        this.props = props;
    }

    @GetMapping("/config")
    public PublicConfig config() {
        return new PublicConfig(auth.passwordResetAvailable(), props.auth().requireEmailVerification(),
                props.ocr().enabled(), props.maxUploadMb());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody Credentials body, HttpServletRequest request) {
        throttle(request);
        return auth.register(body.email(), body.password());
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody Credentials body, HttpServletRequest request) {
        throttle(request);
        return auth.login(body.email(), body.password());
    }

    /** Always 202, whether or not the address is registered. 501 when the server has no e-mail configured. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void forgotPassword(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        throttle(request);
        auth.forgotPassword(body.email());
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetRequest body, HttpServletRequest request) {
        throttle(request);
        auth.resetPassword(body.token(), body.newPassword());
    }

    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody TokenRequest body, HttpServletRequest request) {
        throttle(request);
        auth.verifyEmail(body.token());
    }

    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void resendVerification(@Valid @RequestBody EmailRequest body, HttpServletRequest request) {
        throttle(request);
        auth.resendVerification(body.email());
    }

    /** Requires a token. Revokes every token of this user, including the one used for this call. */
    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutEverywhere(@AuthenticationPrincipal Jwt jwt) {
        auth.logoutEverywhere(id(jwt));
    }

    /** Requires a token. Revokes all old tokens and returns a new one for this device. */
    @PostMapping("/change-password")
    public AuthResponse changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest body,
                                       HttpServletRequest request) {
        throttle(request);
        return auth.changePassword(id(jwt), body.currentPassword(), body.newPassword());
    }

    private void throttle(HttpServletRequest request) {
        limiter.check("auth:" + request.getRemoteAddr(), props.rateLimit().authPerMinute());
    }
}
