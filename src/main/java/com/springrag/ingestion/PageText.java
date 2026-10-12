package com.springrag.ingestion;

/** Text of one page, 1-based. Plain text files are split into pages on form feed characters. */
public record PageText(int pageNumber, String text) {
}
