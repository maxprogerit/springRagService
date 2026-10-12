package com.springrag.ingestion;

/** A document cannot be processed. The message is shown to the user as the document's error_message. */
public class IngestionException extends RuntimeException {

    public IngestionException(String message) {
        super(message);
    }
}
