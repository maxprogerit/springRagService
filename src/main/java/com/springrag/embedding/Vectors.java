package com.springrag.embedding;

/** Conversions for the pgvector text format, e.g. {@code [0.1,0.2,0.3]}. */
public final class Vectors {

    private Vectors() {
    }

    public static String toLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 10 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) {
                throw new IllegalArgumentException("Embedding contains NaN or Infinity at index " + i);
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
