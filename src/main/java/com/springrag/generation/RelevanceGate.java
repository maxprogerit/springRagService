package com.springrag.generation;

import com.springrag.retrieval.FusedHit;
import com.springrag.retrieval.KeywordQuery;
import java.util.List;

/** Decides which retrieved chunks are allowed to reach the language model. */
public final class RelevanceGate {

    private RelevanceGate() {
    }

    /**
     * A chunk passes when it is close enough in meaning (cosine similarity >= minSimilarity), or when the keyword
     * search found it AND it really contains the question's words (at least two of them, or the only one).
     * A single shared common word is not enough.
     */
    public static boolean passes(FusedHit hit, List<String> questionTerms, double minSimilarity) {
        if (hit.vectorSimilarity() != null && hit.vectorSimilarity() >= minSimilarity) {
            return true;
        }
        return hit.keywordRank() != null
                && KeywordQuery.countMatches(hit.text(), questionTerms) >= Math.min(2, questionTerms.size());
    }
}
