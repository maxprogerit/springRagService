package com.springrag.retrieval;

/** One retrieved chunk with the score of the retriever that found it (cosine similarity or ts_rank_cd). */
public record ScoredChunk(long chunkId, long documentId, String filename, int page, int chunkIndex, String text,
                          double score) {
}
