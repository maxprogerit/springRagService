package com.springrag.retrieval;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: score(chunk) = sum over retrievers of 1 / (k + rank).
 * Only ranks are used, so cosine similarity and ts_rank_cd (different scales) can be combined without normalising.
 */
public final class RrfFusion {

    /** The constant from the original RRF paper; it damps the advantage of rank 1 over rank 2. */
    public static final int DEFAULT_K = 60;

    private RrfFusion() {
    }

    public static List<FusedHit> fuse(List<ScoredChunk> vector, List<ScoredChunk> keyword, int k) {
        Map<Long, Builder> byChunk = new LinkedHashMap<>();
        for (int i = 0; i < vector.size(); i++) {
            ScoredChunk c = vector.get(i);
            Builder b = byChunk.computeIfAbsent(c.chunkId(), id -> new Builder(c));
            b.vectorRank = i + 1;
            b.vectorSimilarity = c.score();
            b.score += 1.0 / (k + i + 1);
        }
        for (int i = 0; i < keyword.size(); i++) {
            ScoredChunk c = keyword.get(i);
            Builder b = byChunk.computeIfAbsent(c.chunkId(), id -> new Builder(c));
            b.keywordRank = i + 1;
            b.keywordScore = c.score();
            b.score += 1.0 / (k + i + 1);
        }
        return byChunk.values().stream()
                .map(Builder::build)
                // Ties (same RRF score) are broken by chunk id so the order is deterministic.
                .sorted(Comparator.comparingDouble(FusedHit::score).reversed().thenComparingLong(FusedHit::chunkId))
                .toList();
    }

    private static final class Builder {
        final ScoredChunk chunk;
        double score;
        Integer vectorRank;
        Double vectorSimilarity;
        Integer keywordRank;
        Double keywordScore;

        Builder(ScoredChunk chunk) {
            this.chunk = chunk;
        }

        FusedHit build() {
            return new FusedHit(chunk.chunkId(), chunk.documentId(), chunk.filename(), chunk.page(), chunk.text(),
                    score, vectorRank, vectorSimilarity, keywordRank, keywordScore);
        }
    }
}
