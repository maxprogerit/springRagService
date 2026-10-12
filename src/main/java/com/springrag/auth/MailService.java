package com.springrag.auth;

/** Sends plain-text e-mail. Used for e-mail verification and password reset links. */
public interface MailService {

    /** False when no SMTP server is configured (MAIL_HOST); features that need e-mail are then unavailable. */
    boolean enabled();

    void send(String to, String subject, String text);
}
