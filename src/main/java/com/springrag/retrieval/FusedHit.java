package com.springrag.retrieval;

/**
 * A chunk after Reciprocal Rank Fusion. {@code score} is the RRF score (only meaningful for ordering);
 * the per-retriever ranks (1-based) and scores show why the chunk ended up here. A rank is null when
 * that retriever did not return the chunk.
 */
public record FusedHit(long chunkId, long documentId, String filename, int page, String text, double score,
                       Integer vectorRank, Double vectorSimilarity, Integer keywordRank, Double keywordScore) {
}
