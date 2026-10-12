package com.springrag.support;

import com.springrag.retrieval.KeywordQuery;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic bag-of-words "embedding" used instead of OpenAI in tests: each word is hashed into one of
 * {@code DIMENSION} slots and the vector is L2-normalised. It captures word overlap only, NOT meaning, so
 * numbers produced with it say nothing about the quality of a real embedding model.
 */
public final class HashingEmbedder {

    public static final int DIMENSION = 256;
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");

    private HashingEmbedder() {
    }

    public static List<float[]> embedAll(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            result.add(embed(text));
        }
        return result;
    }

    public static float[] embed(String text) {
        float[] v = new float[DIMENSION];
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String word = m.group();
            if (KeywordQuery.isStopWord(word)) {
                continue;
            }
            int h = word.hashCode();
            v[(h & 0x7fffffff) % DIMENSION] += ((h >>> 16) & 1) == 0 ? 1f : -1f;
        }
        v[0] += 0.001f; // never an all-zero vector: cosine distance to it is undefined
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        float scale = (float) (1.0 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) {
            v[i] *= scale;
        }
        return v;
    }
}
