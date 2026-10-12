package com.springrag.document;

import java.time.Instant;

public record DocumentView(long id, String filename, String contentType, long sizeBytes, Integer pageCount,
                           DocumentStatus status, String errorMessage, Instant createdAt) {

    public static DocumentView of(Document d) {
        return new DocumentView(d.getId(), d.getFilename(), d.getContentType(), d.getSizeBytes(), d.getPageCount(),
                d.getStatus(), d.getErrorMessage(), d.getCreatedAt());
    }
}
