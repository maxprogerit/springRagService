package com.springrag.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.springrag.retrieval.FusedHit;
import java.util.List;
import org.junit.jupiter.api.Test;

class RelevanceGateTest {

    private static FusedHit hit(String text, Double similarity, Integer keywordRank) {
        return new FusedHit(1, 1, "f.txt", 1, text, 0.03, similarity == null ? null : 1, similarity, keywordRank,
                keywordRank == null ? null : 1.0);
    }

    private static final List<String> TERMS = List.of("refund", "annual", "subscriptions");

    @Test
    void closeEnoughInMeaningPassesWithoutAnyKeywordMatch() {
        assertThat(RelevanceGate.passes(hit("unrelated words", 0.35, null), TERMS, 0.20)).isTrue();
    }

    @Test
    void farInMeaningAndNoKeywordMatchIsBlocked() {
        assertThat(RelevanceGate.passes(hit("unrelated words", 0.05, null), TERMS, 0.20)).isFalse();
    }

    @Test
    void aSingleSharedCommonWordIsNotEnough() {
        assertThat(RelevanceGate.passes(hit("The annual picnic is in May", 0.05, 1), TERMS, 0.20)).isFalse();
    }

    @Test
    void twoSharedQuestionWordsPassEvenWhenMeaningIsFar() {
        assertThat(RelevanceGate.passes(hit("Annual subscriptions end in May", 0.05, 1), TERMS, 0.20)).isTrue();
    }

    @Test
    void aOneWordQuestionNeedsThatOneWord() {
        assertThat(RelevanceGate.passes(hit("The word Zanzibar appears here", 0.01, 1), List.of("zanzibar"), 0.20)).isTrue();
        assertThat(RelevanceGate.passes(hit("Nothing here", 0.01, null), List.of("zanzibar"), 0.20)).isFalse();
    }

    @Test
    void aKeywordRankWithoutTheWordsInTheTextDoesNotCount() {
        // e.g. a chunk found through a different language form; the words must really be there.
        assertThat(RelevanceGate.passes(hit("Different text", 0.0, 3), TERMS, 0.20)).isFalse();
    }
}
