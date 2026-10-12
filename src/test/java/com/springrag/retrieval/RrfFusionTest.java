package com.springrag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class RrfFusionTest {

    private static ScoredChunk chunk(long id, double score) {
        return new ScoredChunk(id, 1, "doc.txt", 1, (int) id, "text " + id, score);
    }

    @Test
    void chunkFoundByBothRetrieversBeatsChunksFoundByOne() {
        // 1 is first in vector only, 2 is first in keyword only, 3 is second in both.
        List<ScoredChunk> vector = List.of(chunk(1, 0.9), chunk(3, 0.5));
        List<ScoredChunk> keyword = List.of(chunk(2, 7.0), chunk(3, 3.0));

        List<FusedHit> fused = RrfFusion.fuse(vector, keyword, 60);

        assertThat(fused).extracting(FusedHit::chunkId).containsExactly(3L, 1L, 2L);
        assertThat(fused.get(0).score()).isCloseTo(2.0 / 62, within(1e-12));
        assertThat(fused.get(1).score()).isCloseTo(1.0 / 61, within(1e-12));
    }

    @Test
    void keepsPerRetrieverRanksAndScores() {
        List<FusedHit> fused = RrfFusion.fuse(List.of(chunk(1, 0.9), chunk(2, 0.4)), List.of(chunk(2, 5.5)), 60);

        FusedHit two = fused.stream().filter(h -> h.chunkId() == 2).findFirst().orElseThrow();
        assertThat(two.vectorRank()).isEqualTo(2);
        assertThat(two.vectorSimilarity()).isEqualTo(0.4);
        assertThat(two.keywordRank()).isEqualTo(1);
        assertThat(two.keywordScore()).isEqualTo(5.5);
        FusedHit one = fused.stream().filter(h -> h.chunkId() == 1).findFirst().orElseThrow();
        assertThat(one.keywordRank()).isNull();
        assertThat(one.keywordScore()).isNull();
    }

    @Test
    void singleListKeepsItsOrder() {
        List<FusedHit> fused = RrfFusion.fuse(List.of(chunk(5, 0.9), chunk(4, 0.8), chunk(9, 0.1)), List.of(), 60);

        assertThat(fused).extracting(FusedHit::chunkId).containsExactly(5L, 4L, 9L);
    }

    @Test
    void ties_areBrokenByChunkIdForStableOutput() {
        List<FusedHit> fused = RrfFusion.fuse(List.of(chunk(8, 0.9)), List.of(chunk(3, 9.0)), 60);

        assertThat(fused).extracting(FusedHit::chunkId).containsExactly(3L, 8L);
    }

    @Test
    void emptyInputsGiveEmptyResult() {
        assertThat(RrfFusion.fuse(List.of(), List.of(), 60)).isEmpty();
    }
}
