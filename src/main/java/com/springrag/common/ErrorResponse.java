package com.springrag.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** The one error body used by every endpoint, including security failures. */
public record ErrorResponse(Instant timestamp, int status, String code, String message, String path) {

    public static ErrorResponse of(HttpStatus status, String code, String message, String path) {
        return new ErrorResponse(Instant.now(), status.value(), code, message, path);
    }

    /** For security handlers that run outside Spring MVC exception handling. */
    public static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
                             HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), of(status, code, message, request.getRequestURI()));
    }
}
