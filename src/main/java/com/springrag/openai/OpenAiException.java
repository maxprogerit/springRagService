package com.springrag.openai;

import com.springrag.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The OpenAI call failed. The message is safe to show to users: it never contains the API key,
 * the request body or the provider's raw error text.
 */
public class OpenAiException extends ApiException {

    private final int providerStatus;

    public OpenAiException(int providerStatus, String message) {
        super(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", message);
        this.providerStatus = providerStatus;
    }

    /** HTTP status returned by OpenAI, or 0 when there was no HTTP response (timeout, not configured). */
    public int providerStatus() {
        return providerStatus;
    }
}
