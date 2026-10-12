package com.springrag.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmbeddingSchemaCheckVersionTest {

    private static final int[] MIN = {0, 8, 0};

    @Test
    void comparesVersionsNumerically() {
        assertThat(EmbeddingSchemaCheck.atLeast("0.8.0", MIN)).isTrue();
        assertThat(EmbeddingSchemaCheck.atLeast("0.8.1", MIN)).isTrue();
        assertThat(EmbeddingSchemaCheck.atLeast("0.10.0", MIN)).isTrue();
        assertThat(EmbeddingSchemaCheck.atLeast("1.0", MIN)).isTrue();
        assertThat(EmbeddingSchemaCheck.atLeast("0.7.4", MIN)).isFalse();
        assertThat(EmbeddingSchemaCheck.atLeast("0.5.1", MIN)).isFalse();
    }

    @Test
    void vectorLiteralRejectsNonFiniteValues() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Vectors.toLiteral(new float[] {1f, Float.NaN}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(Vectors.toLiteral(new float[] {1f, -0.5f, 2.5e-5f})).isEqualTo("[1.0,-0.5,2.5E-5]");
    }
}
