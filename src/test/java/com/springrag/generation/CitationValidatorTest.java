package com.springrag.generation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CitationValidatorTest {

    @Test
    void keepsValidCitationsInOrderOfFirstAppearance() {
        CitationValidator.Result r = CitationValidator.validate("Fact A [2]. Fact B [1][2]. Fact C [3].", 3);

        assertThat(r.text()).isEqualTo("Fact A [2]. Fact B [1][2]. Fact C [3].");
        assertThat(r.cited()).containsExactly(2, 1, 3);
    }

    @Test
    void dropsNumbersThatAreNotInTheContext() {
        CitationValidator.Result r = CitationValidator.validate("Fact A [1]. Invented [7]. Zero [0].", 2);

        assertThat(r.text()).isEqualTo("Fact A [1]. Invented. Zero.");
        assertThat(r.cited()).containsExactly(1);
    }

    @Test
    void filtersInsideCommaGroups() {
        CitationValidator.Result r = CitationValidator.validate("Both [1, 9, 2].", 2);

        assertThat(r.text()).isEqualTo("Both [1, 2].");
        assertThat(r.cited()).containsExactly(1, 2);
    }

    @Test
    void answerWithoutCitationsHasNoCitedSources() {
        assertThat(CitationValidator.validate("A confident claim with no source.", 3).cited()).isEmpty();
    }

    @Test
    void onlyInvalidCitationsMeansNothingCited() {
        CitationValidator.Result r = CitationValidator.validate("Made up [4].", 3);

        assertThat(r.cited()).isEmpty();
    }

    @Test
    void ignoresBracketsThatAreNotCitations() {
        CitationValidator.Result r = CitationValidator.validate("Use arr[i] and [see below] and [1].", 1);

        assertThat(r.text()).isEqualTo("Use arr[i] and [see below] and [1].");
        assertThat(r.cited()).containsExactly(1);
    }

    @Test
    void hugeNumbersDoNotCrash() {
        CitationValidator.Result r = CitationValidator.validate("x [99999999999999999999] [1]", 1);

        assertThat(r.cited()).containsExactly(1);
        assertThat(r.text()).isEqualTo("x [1]");
    }
}
