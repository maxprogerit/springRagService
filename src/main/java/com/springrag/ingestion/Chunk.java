package com.springrag.ingestion;

/** A passage of one page. {@code chunkIndex} is unique within a document and follows document order. */
public record Chunk(int pageNumber, int chunkIndex, String text) {
}
